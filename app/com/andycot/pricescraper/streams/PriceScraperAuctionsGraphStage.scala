package com.andycot.pricescraper.streams

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream.stage._
import akka.stream.{ActorMaterializerSettings, _}
import akka.util.ByteString
import com.andycot.pricescraper.business.{PriceScraperExtractor, ResourceUnavailable}
import com.andycot.pricescraper.models.{PriceScraperAuction, PriceScraperUrlContent, PriceScraperWebsite}
import com.andycot.pricescraper.services.{PriceScraperAuctionService, PriceScraperUrlService}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperAuctionsGraphStage @Inject()(priceScraperWebsite: PriceScraperWebsite,
                                               priceScraperExtractor: PriceScraperExtractor)
                                              (implicit val priceScraperUrlService: PriceScraperUrlService,
                                               priceScraperAuctionService: PriceScraperAuctionService,
                                               ec: ExecutionContext)
  extends GraphStage[FlowShape[PriceScraperUrlContent, PriceScraperAuction]] {

  val in: Inlet[PriceScraperUrlContent] = Inlet("PriceScraperAuctions.in")
  val out: Outlet[PriceScraperAuction] = Outlet("PriceScraperAuctions.out")
  override val shape: FlowShape[PriceScraperUrlContent, PriceScraperAuction] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var priceScraperAuctions = mutable.Queue[PriceScraperAuction]()

    implicit val system = ActorSystem("andycot")
    implicit val mat: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(system))

    setHandlers(in, out, new InHandler with OutHandler {
      override def onPush(): Unit = {

        grab(in) match {
          case PriceScraperUrlContent(priceScraperUrl, Some(htmlContent)) =>
            Logger.info(s"Processing WEBSITE ${priceScraperUrl.website} URL ${priceScraperUrl.url} w/htmlContent")
            (for {
              auctions <- priceScraperExtractor.extractAuctions(priceScraperWebsite, priceScraperUrl, htmlContent)
              alreadyRecordedAuctions <- priceScraperAuctionService.findMany(auctions)
            } yield (auctions, alreadyRecordedAuctions)).onComplete(processHtmlContentCallback(priceScraperWebsite, priceScraperUrl.url).invoke)

          case PriceScraperUrlContent(priceScraperUrl, None) =>
            Logger.info(s"Processing WEBSITE ${priceScraperUrl.website} URL ${priceScraperUrl.url} w/o htmlContent")
            (for {
              htmlContent <- getHtmlContent(priceScraperUrl.url)
              auctions <- priceScraperExtractor.extractAuctions(priceScraperWebsite, priceScraperUrl, htmlContent)
              alreadyRecordedAuctions <- priceScraperAuctionService.findMany(auctions)
            } yield (auctions, alreadyRecordedAuctions)).onComplete(processHtmlContentCallback(priceScraperWebsite, priceScraperUrl.url).invoke)
        }
      }

      override def onPull(): Unit = {
        // We pull(in) only if we have emptied the auctions queue, this way we process each uri in "sequence"
        if (!pushNextAuction() && !isClosed(in)) {
          maybePullIn()
        }
      }

      override def onUpstreamFinish(): Unit = {
        Logger.info(s"Upstream finished with ${priceScraperAuctions.size} remaining auctions in the queue")
        if (priceScraperAuctions.isEmpty) complete(out)
      }
    })

    /**
      * Pull(in) only if necessary
      */
    def maybePullIn(): Unit = {
      if (!hasBeenPulled(in))
        pull(in)
    }

    /**
      *
      * @param uri The uri from which to grab the html content from
      * @return
      */
    def getHtmlContent(uri: Uri): Future[String] = {
      Logger.info(s"Fetching $uri")

      Http().singleRequest(HttpRequest(uri = uri)).flatMap {
        case res if res.status.isSuccess =>
          res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

        case res =>
          Logger.error(s"Error fetching $uri status ${res.status}")
          throw new ResourceUnavailable(s"Error fetching $uri status ${res.status}")
      }
    }

    /**
      * http://blog.kunicki.org/blog/2016/07/20/implementing-a-custom-akka-streams-graph-stage/
      *
      * @param uri The uri from which the html content was grabbed from, for debug purposes
      * @return
      */
    private def processHtmlContentCallback(priceScraperWebsite: PriceScraperWebsite, uri: Uri) = getAsyncCallback[Try[(Seq[PriceScraperAuction], Seq[PriceScraperAuction])]] {
      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.length == auctions.length && auctions.nonEmpty && priceScraperWebsite.canSortByAuctionEndDate =>
        Logger.info(s"All the auctions are ALREADY recorded for $uri, skipping to next base URL")

        // All auctions are already recorded for the current uri, so we don't need to process
        // this uri next pages (page n+1, page n+2, page n+3, ...)
        // So we cancel the upstream thus putting the unprocessed urls to the bin.
        cancel(in)

        // To complete the stage in a clean way, we complete the downstream ONLY if there's NO remaining
        // auctions in the queue to push downstream.
        if (priceScraperAuctions.isEmpty) complete(out)

      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.length == auctions.length && auctions.nonEmpty && !priceScraperWebsite.canSortByAuctionEndDate =>
        Logger.info(s"All the auctions are ALREADY recorded for $uri, continuing with same base URL")
        maybePullIn()

      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.isEmpty =>
        val newAuctions = getNewAuctions(auctions, alreadyRecordedAuctions)
        Logger.info(s"${newAuctions.length} new auctions found for $uri")

        // Queue the new auctions
        priceScraperAuctions ++= newAuctions

        // Push one auction
        pushNextAuction()

      case Success((auctions, alreadyRecordedAuctions)) if priceScraperWebsite.canSortByAuctionEndDate =>
        val newAuctions = getNewAuctions(auctions, alreadyRecordedAuctions)
        Logger.info(s"${newAuctions.length} new auctions found for $uri")

        // Queue the new auctions
        priceScraperAuctions ++= newAuctions

        // Push one auction
        pushNextAuction()

        // Some new auctions were found, so we push this auctions downstream and we cancel the upstream.
        cancel(in)

        // To complete the stage in a clean way, we complete the downstream ONLY if there's NO remaining
        // auctions in the queue to push downstream.
        if (priceScraperAuctions.isEmpty) complete(out)

      case Success((auctions, alreadyRecordedAuctions)) if !priceScraperWebsite.canSortByAuctionEndDate =>
        val newAuctions = getNewAuctions(auctions, alreadyRecordedAuctions)
        Logger.info(s"${newAuctions.length} new auctions found for $uri")

        // Queue the new auctions
        priceScraperAuctions ++= newAuctions

        // Push one auction
        pushNextAuction()

        maybePullIn()

      case Failure(f) =>
        // TODO refactor ???
        Logger.error(s"Error encountered while processing $uri", f)
    }

    /**
      * Push the next auction if there's one available in the queue
      *
      * @return true if an auction was pushed
      *         false if no auction was pushed
      */
    def pushNextAuction(): Boolean = {
      if (priceScraperAuctions.nonEmpty && isAvailable(out)) {
        push(out, priceScraperAuctions.dequeue)
        true
      } else {
        false
      }
    }

    /**
      * Get a list of elements from the "auctions" list that are not in the "alreadyRecordedAuctions" list
      *
      * @param auctions                A list of auctions eventually containing new auctions
      * @param alreadyRecordedAuctions The auctions from the "auctions" list that are already recorded in mongodb
      * @return
      */
    def getNewAuctions(auctions: Seq[PriceScraperAuction], alreadyRecordedAuctions: Seq[PriceScraperAuction]): Seq[PriceScraperAuction] = {
      auctions.filterNot(auction => alreadyRecordedAuctions.exists(_.auctionId == auction.auctionId))
    }
  }
}
