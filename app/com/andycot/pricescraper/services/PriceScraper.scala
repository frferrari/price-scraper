package com.andycot.pricescraper.services

import javax.inject._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.{Flow, Framing, Source}
import akka.stream._
import akka.util.ByteString
import com.andycot.pricescraper.business.{PriceScraperDCP, ResourceUnavailable}
import com.andycot.pricescraper.models.{PriceScraperAuction, PriceScraperUrl, PriceScraperUrlContent, PriceScraperWebsite}
import com.andycot.pricescraper.streams.{PriceScraperAuctionsGraphStage, PriceScraperUrlGraphStage}
import play.api.Logger
import play.api.inject.ApplicationLifecycle

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.concurrent.duration._

/**
  * Created by Francois FERRARI on 20/06/2017
  */

trait PriceScraper

@Singleton
class PriceScraperImpl @Inject()(implicit priceScraperUrlService: PriceScraperUrlService,
                                 priceScraperAuctionService: PriceScraperAuctionService,
                                 priceScraperWebsiteService: PriceScraperWebsiteService,
                                 ec: ExecutionContext,
                                 appLifecycle: ApplicationLifecycle) extends PriceScraper {

  type BasePriceScraperUrlWithHtmlContent = (PriceScraperUrl, String)

  // When the application starts, register a stop hook with the
  // ApplicationLifecycle object. The code inside the stop hook will
  // be run when the application stops.
  appLifecycle.addStopHook { () =>
    // stopScraping -- KillSwitch
    Future.successful(())
  }

  startScraping

  /**
    *
    * @return
    */
  def startScraping(): Unit = {
    /*
     * http://doc.akka.io/docs/akka-http/current/scala/http/client-side/request-level.html
     */
    implicit val system = ActorSystem("test")

    val decider: Supervision.Decider = {
      case _: ArithmeticException => Supervision.Resume
      case e =>
        Logger.error("Supervision.Decider caught exception", e)
        Supervision.Stop
    }

    implicit val materializer: ActorMaterializer = ActorMaterializer(
      ActorMaterializerSettings(system).withSupervisionStrategy(decider))

    val delimiter: Flow[ByteString, ByteString, NotUsed] =
      Framing.delimiter(
        ByteString("\r\n"),
        maximumFrameLength = 100000,
        allowTruncation = true)

    val numberOfUrlsProcessedInParallel = 1

    /**
      * Produces a list of URLs and their HTML content, this URLs are the base URLs where we can find
      * the first and the last page number where auctions are listed.
      */
    val getHtmlContentFromBaseUrl: Flow[PriceScraperUrl, BasePriceScraperUrlWithHtmlContent, NotUsed] =
      Flow[PriceScraperUrl].mapAsync[BasePriceScraperUrlWithHtmlContent](numberOfUrlsProcessedInParallel) { priceScraperUrl =>
        Logger.info(s"Processing WEBSITE ${priceScraperUrl.website} url ${priceScraperUrl.url}")

        val htmlContentF: Future[String] = Http().singleRequest(HttpRequest(uri = priceScraperUrl.url)).flatMap {
          case res if res.status.isSuccess =>
            res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

          case res =>
            Logger.error(s"Unable to access website ${priceScraperUrl.website} with url ${priceScraperUrl.url} error ${res.status}")
            throw new ResourceUnavailable("sUnable to access website ${priceScraperUrl.website} with url ${priceScraperUrl.url} error ${res.status}")
        }

        htmlContentF.map(htmlContent => priceScraperUrl -> htmlContent)
      }

    /**
      * Generates a list of URLs to eventually parse auctions from,
      * http://www.andycot.fr/...&page=1
      * http://www.andycot.fr/...&page=2
      * http://www.andycot.fr/...&page=3
      * ...
      * http://www.andycot.fr/...&page=26
      */
    def generatePagedUrlsFromBaseUrl(priceScraperWebsites: Seq[PriceScraperWebsite]): Flow[BasePriceScraperUrlWithHtmlContent, Seq[PriceScraperUrlContent], NotUsed] =
      Flow[BasePriceScraperUrlWithHtmlContent].map {
        // TODO other case ...
        case (priceScraperUrl, htmlContent) if priceScraperUrl.website == PriceScraperWebsite.DCP =>
          val priceScraperUrlContents = PriceScraperDCP.getPagedUrls(priceScraperUrl, priceScraperWebsites, htmlContent).foldLeft(Seq.empty[PriceScraperUrlContent]) {
            case (acc, url) if acc.isEmpty =>
              acc :+ PriceScraperUrlContent(priceScraperUrl.copy(url = url), Some(htmlContent))

            case (acc, url) =>
              acc :+ PriceScraperUrlContent(priceScraperUrl.copy(url = url), None)
          }

          priceScraperUrlContents
      }

    //
    //
    //
    val priceScraperUrlGraphStage: Graph[SourceShape[PriceScraperUrl], NotUsed] = new PriceScraperUrlGraphStage
    val priceScraperUrlSource: Source[PriceScraperUrl, NotUsed] = Source.fromGraph(priceScraperUrlGraphStage)

    val priceScraperAuctionsGraphStage: PriceScraperAuctionsGraphStage = new PriceScraperAuctionsGraphStage
    val priceScraperAuctionsFlow: Flow[PriceScraperUrlContent, PriceScraperAuction, NotUsed] = Flow.fromGraph(priceScraperAuctionsGraphStage)

    priceScraperWebsiteService.findAll.map { priceScraperWebsites =>
      priceScraperUrlSource
        .throttle(1, imNotARobot(30, 30), 1, ThrottleMode.Shaping)
        .via(getHtmlContentFromBaseUrl)
        .via(generatePagedUrlsFromBaseUrl(priceScraperWebsites))
        .flatMapConcat(urls =>
          Source
            .fromIterator(() => urls.toIterator)
            .throttle(1, imNotARobot(10, 10), 1, ThrottleMode.Shaping)
            .via(priceScraperAuctionsFlow)
        )
        .map { auction =>
          priceScraperAuctionService.createOne(auction).recover {
            case NonFatal(e) => Logger.error("MongoDB persistence error", e)
          }
          auction
        }
        .runForeach(printOut)
    }.recover {
      case NonFatal(e) =>
        Logger.error("Error reading website parameters", e)
    }

    ()
  }

  /**
    *
    * @param base
    * @param range
    * @return
    */
  def imNotARobot(base: Int, range: Int): FiniteDuration = {
    val r = scala.util.Random
    (base + r.nextInt(range)).seconds
  }

  /**
    *
    * @param priceScraperAuction
    */
  def printOut(priceScraperAuction: PriceScraperAuction): Unit = {
    // println(s"AuctionController.p4 received auctionId ================> ${priceScraperAuction.auctionId}")
    // Thread.sleep(50)
  }

  //
  // This code allows to do nearly the same thing as the PriceScraperAuctionsGraphStage
  //
  //    val t = priceScraperUrlSource
  //      .via(getHtmlContentFromBaseUrl)
  //      .via(generatePagedUrlsFromBaseUrl)
  //      .flatMapConcat { urls =>
  //        Source.unfoldAsync(urls) {
  //          case (PriceScraperUrlContent(url, Some(htmlContent)) :: tail) =>
  //            Logger.info(s"ExtractAuctions $url")
  //            val auctions: List[PriceScraperAuction] = PriceScraperDCP.extractAuctions(htmlContent)
  //            val alreadyRecorded = priceScraperUrlService.auctionsAlreadyRecorded(auctions)
  //
  //            Logger.info(s"PriceScraperAuctionsGraphStage.processHtmlContent auctionIds=${auctions.map(_.auctionId)}")
  //
  //            if (alreadyRecorded.length == auctions.length && auctions.nonEmpty) {
  //              Future.successful(None)
  //            } else {
  //              Future.successful(Some(tail, alreadyRecorded))
  //            }
  //
  //          case (PriceScraperUrlContent(url, None) :: tail) =>
  //            Logger.info(s"call getHtmlContent $url")
  //            getHtmlContent(url).map { htmlContent =>
  //              Logger.info(s"ExtractionAuctions $url")
  //              val auctions: List[PriceScraperAuction] = PriceScraperDCP.extractAuctions(htmlContent)
  //              val alreadyRecorded: Seq[PriceScraperAuction] = priceScraperUrlService.auctionsAlreadyRecorded(auctions)
  //
  //              Logger.info(s"auctionIds=${auctions.map(_.auctionId)}")
  //
  //              if (alreadyRecorded.length == auctions.length && auctions.nonEmpty) {
  //                None
  //              } else {
  //                Some(tail, alreadyRecorded)
  //              }
  //            }
  //
  //          case _ =>
  //            Logger.info("None")
  //            Future.successful(None)
  //        }
  //      }
  //      .runForeach(p4seq)
}