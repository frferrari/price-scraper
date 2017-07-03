package com.andycot.pricescraper.services

import java.util
import javax.inject._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream._
import akka.stream.scaladsl.{Flow, Framing, Source}
import akka.util.ByteString
import com.andycot.pricescraper.business.{PriceScraperDCP, PriceScraperExtractor, ResourceUnavailable}
import com.andycot.pricescraper.models._
import com.andycot.pricescraper.streams.{PriceScraperAuctionsGraphStage, PriceScraperUrlGraphStage}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.Logger
import play.api.inject.ApplicationLifecycle

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import scala.util.matching.Regex

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
    // TODO stopScraping -- KillSwitch
    Future.successful(())
  }

  startScraping()

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
    val numberOfAuctionsFetchedInParallel = 2

    /**
      * Produces a list of URLs and their HTML content, this URLs are the base URLs where we can find
      * the first and the last page number where auctions are listed.
      */
    val getHtmlContentFromBaseUrl: Flow[PriceScraperUrl, BasePriceScraperUrlWithHtmlContent, NotUsed] =
      Flow[PriceScraperUrl].mapAsync[BasePriceScraperUrlWithHtmlContent](numberOfUrlsProcessedInParallel) { priceScraperUrl =>
        Logger.info(s"Processing WEBSITE ${priceScraperUrl.website} url ${priceScraperUrl.url}")

        val htmlContentF: Future[String] =
          Http().singleRequest(HttpRequest(uri = priceScraperUrl.url))
            .flatMap {
              case res if res.status.isSuccess =>
                res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

              case res =>
                Logger.error(s"Unable to access website ${priceScraperUrl.website} with url ${priceScraperUrl.url} error ${res.status}")
                throw new ResourceUnavailable(s"Unable to access website ${priceScraperUrl.website} with url ${priceScraperUrl.url} error ${res.status}")
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
    def generatePagedUrlsFromBaseUrl(priceScraperWebsite: PriceScraperWebsite, priceScraperExtractor: PriceScraperExtractor): Flow[BasePriceScraperUrlWithHtmlContent, Seq[PriceScraperUrlContent], NotUsed] =
      Flow[BasePriceScraperUrlWithHtmlContent].map {
        case (priceScraperUrl, htmlContent) if priceScraperUrl.website == PriceScraperWebsite.DCP =>
          val priceScraperUrlContents = priceScraperExtractor.getPagedUrls(priceScraperUrl, priceScraperWebsite, htmlContent).foldLeft(Seq.empty[PriceScraperUrlContent]) {
            case (acc, psu) if acc.isEmpty =>
              acc :+ PriceScraperUrlContent(psu, Some(htmlContent))

            case (acc, psu) =>
              acc :+ PriceScraperUrlContent(psu, None)
          }

          priceScraperUrlContents

        case _ =>
          Nil
      }

    /**
      * Fetches the content of auction pages and scraps informations to update the auction
      */
    def fetchAuctionInformations(priceScraperExtractor: PriceScraperExtractor): Flow[PriceScraperAuction, PriceScraperAuction, NotUsed] =
      Flow[PriceScraperAuction].mapAsync[PriceScraperAuction](numberOfAuctionsFetchedInParallel) { priceScraperAuction =>
        Logger.info(s"Processing WEBSITE ${priceScraperAuction.website} auctionId ${priceScraperAuction.auctionId} url ${priceScraperAuction.auctionUrl}")

        val htmlContentF: Future[String] =
          Http().singleRequest(HttpRequest(uri = priceScraperAuction.auctionUrl))
            .flatMap {
              case res if res.status.isSuccess =>
                res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

              case res =>
                Logger.error(s"Unable to access auctionId ${priceScraperAuction.auctionId} website ${priceScraperAuction.website} with url ${priceScraperAuction.auctionUrl} error ${res.status}")
                throw new ResourceUnavailable(s"Unable to access auctionId ${priceScraperAuction.auctionId} website ${priceScraperAuction.website} with url ${priceScraperAuction.auctionUrl} error ${res.status}")
            }

        htmlContentF.map {
          case htmlContent if priceScraperAuction.website == PriceScraperWebsite.DCP =>
            priceScraperExtractor.extractAuctionInformations(priceScraperAuction, htmlContent)

          case _ =>
            Logger.info(s"fetchAuctionInformations: Unknown website ${priceScraperAuction.website}, won't scrap auctionInformations")
            priceScraperAuction
        }
      }

    //    Source
    //      .fromFuture(priceScraperWebsiteService.findAll)
    //      .flatMapConcat(websites =>
    //        Source
    //          .fromIterator(() => websites.toIterator)
    //          .map(website => Seq(website))
    //            .via(getHtmlContentFromBaseUrl)
    //      )

    val withPriceScraperExtractor: PartialFunction[PriceScraperWebsite, (PriceScraperWebsite, PriceScraperExtractor)] = {
      case priceScraperWebsite if priceScraperWebsite.website == "DCP" =>
        (priceScraperWebsite, PriceScraperDCP)
    }

    Source
      .fromFuture(priceScraperWebsiteService.findAll)
      .expand(_.toIterator)
      .collect(withPriceScraperExtractor)
      .mapAsync(4) { case (priceScraperWebsite, priceScraperExtractor) =>
        implicit val priceScraperWebsiteImplicit = priceScraperWebsite
        implicit val priceScraperExtractorImplicit = priceScraperExtractor
        Logger.info(s"Scraping website ${priceScraperWebsite.website}")

        val priceScraperUrlGraphStage: Graph[SourceShape[PriceScraperUrl], NotUsed] = new PriceScraperUrlGraphStage
        val priceScraperUrlsFlow: Source[PriceScraperUrl, NotUsed] = Source.fromGraph(priceScraperUrlGraphStage)

        val priceScraperAuctionsGraphStage: PriceScraperAuctionsGraphStage = new PriceScraperAuctionsGraphStage
        val priceScraperAuctionsFlow: Flow[PriceScraperUrlContent, PriceScraperAuction, NotUsed] = Flow.fromGraph(priceScraperAuctionsGraphStage)

        priceScraperUrlsFlow
          //        .throttle(1, imNotARobot(1000, 2000), 1, ThrottleMode.Shaping)
          .via(getHtmlContentFromBaseUrl)
          .via(generatePagedUrlsFromBaseUrl(priceScraperWebsite, priceScraperExtractor))
          .flatMapConcat(urls =>
            Source
              .fromIterator(() => urls.toIterator)
              .throttle(1, imNotARobot(200, 4000), 1, ThrottleMode.Shaping)
              .via(priceScraperAuctionsFlow)
          )
          .throttle(1, imNotARobot(400, 600), 1, ThrottleMode.Shaping)
          .via(fetchAuctionInformations(priceScraperExtractor))
          .map { auction =>
            priceScraperAuctionService.createOne(auction).recover {
              case NonFatal(e) => Logger.error("MongoDB persistence error", e)
            }
            auction
          }
          .runForeach(printOut)
      }
      .runForeach(println)

    /*
     * Let's go scraping
     */
    /*
    priceScraperWebsiteService.findAll.map { implicit priceScraperWebsites =>
      /*
       * Flows & Custom Graph Stages
       */

      priceScraperUrlsFlow
        //        .throttle(1, imNotARobot(1000, 2000), 1, ThrottleMode.Shaping)
        .via(getHtmlContentFromBaseUrl)
        .via(generatePagedUrlsFromBaseUrl(priceScraperWebsites))
        .flatMapConcat(urls =>
          Source
            .fromIterator(() => urls.toIterator)
            .throttle(1, imNotARobot(200, 4000), 1, ThrottleMode.Shaping)
            .via(priceScraperAuctionsFlow)
        )
        .throttle(1, imNotARobot(400, 600), 1, ThrottleMode.Shaping)
        .via(fetchAuctionInformations)
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
    */

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
    (base + r.nextInt(range)).milliseconds
  }

  /**
    *
    * @param priceScraperAuction
    */
  def printOut(priceScraperAuction: PriceScraperAuction): Unit = {
    // println(s"AuctionController.p4 received auctionId ================> ${priceScraperAuction.auctionId}")
    // Thread.sleep(50)
  }
}