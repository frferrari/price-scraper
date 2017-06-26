package com.andycot.pricescraper.streams

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.stage._
import akka.stream.{ActorMaterializerSettings, _}
import akka.util.ByteString
import com.andycot.pricescraper.business.{PriceScraperDCP, ResourceUnavailable}
import com.andycot.pricescraper.models.{PriceScraperAuction, PriceScraperUrl, PriceScraperUrlContent, PriceScraperWebsite}
import com.andycot.pricescraper.services.{PriceScraperAuctionService, PriceScraperUrlService}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperAuctionsGraphStage @Inject()(implicit val priceScraperUrlService: PriceScraperUrlService,
                                               priceScraperWebsites: Seq[PriceScraperWebsite],
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
          case PriceScraperUrlContent(PriceScraperUrl(website, url), Some(htmlContent)) =>
            getPriceScraperWebsite(website) match {
              case Some(priceScraperWebsite) =>
                Logger.info(s"Processing WEBSITE $website URL $url w/htmlContent")
                (for {
                  auctions <- PriceScraperDCP.extractAuctions(website, htmlContent)
                  alreadyRecordedAuctions <- priceScraperAuctionService.findMany(auctions)
                } yield (auctions, alreadyRecordedAuctions)).onComplete(processHtmlContentCallback(priceScraperWebsite, url).invoke)

              case None =>
                Logger.error(s"Unknown WEBSITE $website for URL $url w/htmlContent")
                pull(in)
            }

          case PriceScraperUrlContent(PriceScraperUrl(website, url), None) =>
            getPriceScraperWebsite(website) match {
              case Some(priceScraperWebsite) =>
                Logger.info(s"Processing WEBSITE $website URL $url w/o htmlContent")
                (for {
                  htmlContent <- getHtmlContent(url)
                  auctions <- PriceScraperDCP.extractAuctions(website, htmlContent)
                  alreadyRecordedAuctions <- priceScraperAuctionService.findMany(auctions)
                } yield (auctions, alreadyRecordedAuctions)).onComplete(processHtmlContentCallback(priceScraperWebsite, url).invoke)

              case None =>
                Logger.error(s"Unknown WEBSITE $website for URL $url w/o htmlContent")
                pull(in)
            }
        }
      }

      override def onPull(): Unit = {
        // We pull(in) only if we have emptied the auctions queue, this way we process each url in "sequence"
        if (!pushNextAuction() && !isClosed(in)) {
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        Logger.info(s"Upstream finished with ${priceScraperAuctions.size} remaining auctions in the queue")
        if (priceScraperAuctions.isEmpty) complete(out)
      }
    })

    /**
      *
      * @param url The url from which to grab the html content from
      * @return
      */
    def getHtmlContent(url: String): Future[String] = {
      Logger.info(s"Fetching $url")

      Http().singleRequest(HttpRequest(uri = url)).flatMap {
        case res if res.status.isSuccess =>
          res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

        case res =>
          Logger.error(s"Error fetching $url status ${res.status}")
          throw new ResourceUnavailable(s"Error fetching $url status ${res.status}")
      }
    }

    /**
      * http://blog.kunicki.org/blog/2016/07/20/implementing-a-custom-akka-streams-graph-stage/
      *
      * @param url The url from which the html content was grabbed from, for debug purposes
      * @return
      */
    private def processHtmlContentCallback(priceScraperWebsite: PriceScraperWebsite, url: String) = getAsyncCallback[Try[(Seq[PriceScraperAuction], Seq[PriceScraperAuction])]] {
      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.length == auctions.length && auctions.nonEmpty && priceScraperWebsite.canSortByAuctionEndDate =>
        Logger.info(s"All the auctions are ALREADY recorded for $url, skipping to next base URL")

        // All auctions are already recorded for the current url, so we don't need to process
        // this url next pages (page n+1, page n+2, page n+3, ...)
        // So we cancel the upstream thus putting the unprocessed urls to the bin.
        cancel(in)

        // To complete the stage in a clean way, we complete the downstream ONLY if there's NO remaining
        // auctions in the queue to push downstream.
        if (priceScraperAuctions.isEmpty) complete(out)

      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.length == auctions.length && auctions.nonEmpty && !priceScraperWebsite.canSortByAuctionEndDate =>
        Logger.info(s"All the auctions are ALREADY recorded for $url, continuing with same base URL")

        if (!hasBeenPulled(in)) pull(in)

      case Success((auctions, alreadyRecordedAuctions)) if alreadyRecordedAuctions.isEmpty =>
        val newAuctions = getNewAuctions(auctions, alreadyRecordedAuctions)
        Logger.info(s"${newAuctions.length} new auctions found for $url")

        // Queue the new auctions
        priceScraperAuctions ++= newAuctions

        // Push one auction
        pushNextAuction()

      case Success((auctions, alreadyRecordedAuctions)) if priceScraperWebsite.canSortByAuctionEndDate =>
        val newAuctions = getNewAuctions(auctions, alreadyRecordedAuctions)
        Logger.info(s"${newAuctions.length} new auctions found for $url")

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
        Logger.info(s"${newAuctions.length} new auctions found for $url")

        // Queue the new auctions
        priceScraperAuctions ++= newAuctions

        // Push one auction
        pushNextAuction()

        if (!hasBeenPulled(in)) pull(in)

      case Failure(f) =>
        // TODO refactor ???
        Logger.error(s"Error encountered while processing $url", f)
    }

    /**
      * Push the next auction if there's one available in the queue
      * @return true if an auction was pushed
      *         false if no auction was pushed
      */
    def pushNextAuction(): Boolean = {
      if (priceScraperAuctions.nonEmpty) {
        push(out, priceScraperAuctions.dequeue)
        true
      } else {
        false
      }
    }

    /**
      * Get a list of elements from the "auctions" list that are not in the "alreadyRecordedAuctions" list
      * @param auctions A list of auctions eventually containing new auctions
      * @param alreadyRecordedAuctions The auctions from the "auctions" list that are already recorded in mongodb
      * @return
      */
    def getNewAuctions(auctions: Seq[PriceScraperAuction], alreadyRecordedAuctions: Seq[PriceScraperAuction]): Seq[PriceScraperAuction] = {
      auctions.filterNot(auction => alreadyRecordedAuctions.exists(_.auctionId == auction.auctionId))
    }


    /**
      * Finds a PriceScraperWebsite from a website
      * @param website
      * @return A Some(PriceScraperWebsite) for an existing website
      *         A None when the website was not found
      */
    def getPriceScraperWebsite(website: String): Option[PriceScraperWebsite] = priceScraperWebsites.find(_.website == website)

  }
}
