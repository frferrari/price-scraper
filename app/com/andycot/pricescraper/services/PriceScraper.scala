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
import com.andycot.pricescraper.business.{PriceScraperDCP, ResourceUnavailable}
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
    def generatePagedUrlsFromBaseUrl(priceScraperWebsites: Seq[PriceScraperWebsite]): Flow[BasePriceScraperUrlWithHtmlContent, Seq[PriceScraperUrlContent], NotUsed] =
      Flow[BasePriceScraperUrlWithHtmlContent].map {
        case (priceScraperUrl, htmlContent) if priceScraperUrl.website == PriceScraperWebsite.DCP =>
          val priceScraperUrlContents = PriceScraperDCP.getPagedUrls(priceScraperUrl, priceScraperWebsites, htmlContent).foldLeft(Seq.empty[PriceScraperUrlContent]) {
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
    val fetchAuctionInformations: Flow[PriceScraperAuction, PriceScraperAuction, NotUsed] =
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
            PriceScraperDCP.extractAuctionInformations(priceScraperAuction, htmlContent)

          case _ =>
            Logger.info(s"fetchAuctionInformations: Unknown website ${priceScraperAuction.website}, won't scrap auctionInformations")
            priceScraperAuction
        }
      }

    /*
     * Let's go scraping
     */
    priceScraperWebsiteService.findAll.map { implicit priceScraperWebsites =>
      /*
       * Flows & Custom Graph Stages
       */
      val priceScraperUrlGraphStage: Graph[SourceShape[PriceScraperUrl], NotUsed] = new PriceScraperUrlGraphStage
      val priceScraperUrlsFlow: Source[PriceScraperUrl, NotUsed] = Source.fromGraph(priceScraperUrlGraphStage)

      val priceScraperAuctionsGraphStage: PriceScraperAuctionsGraphStage = new PriceScraperAuctionsGraphStage
      val priceScraperAuctionsFlow: Flow[PriceScraperUrlContent, PriceScraperAuction, NotUsed] = Flow.fromGraph(priceScraperAuctionsGraphStage)

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

    ()
  }

//    override def extractAuctions(website: String, htmlContent: String): Future[Seq[PriceScraperAuction]] = Future {
//      @tailrec def extractAuction(elementsIterator: util.Iterator[Element], priceScraperAuctions: Seq[PriceScraperAuction] = Nil): Seq[PriceScraperAuction] = {
//        elementsIterator.hasNext match {
//          case true =>
//            val element: Element = elementsIterator.next()
//            val imageContainer = element.select("div.item-content > div.image-container")
//            val auctionIdRegex(auctionId) = element.attr("id")
//
//            if (imageContainer.select("a.img-view").hasClass("default-thumb")) {
//              Logger.info(s"PriceCrawlerDCP.extractAuction auction $auctionId has no picture, skipping ...")
//              extractAuction(elementsIterator, priceScraperAuctions)
//            } else {
//
//              val auctionId = imageContainer.select("a.img-view").attr("data-item-id").trim
//              val largeUrl = element.select("a.img-view").attr("href").trim
//              //
//              val imgThumbElement = imageContainer.select("img.image-thumb")
//              val auctionTitle = imgThumbElement.attr("alt").trim
//              val thumbUrl = imgThumbElement.attr("data-original").trim
//
//              //
//              val itemFooterElement = element.select("div.item-content > div.item-footer")
//              val auctionUrl = itemFooterElement.select("a.item-link").attr("href").trim
//              val itemPrice = itemFooterElement.select(".item-price").text().trim
//
//              val sellingType = itemFooterElement.select("div.selling-type-right > span.selling-type-text")
//              val auctionTypeAndNrBids = Try(if (sellingType.text().contains(sellingTypeFixedPrice)) {
//                (PriceScraperAuction.FIXED_PRICE, None)
//              } else {
//                val sellingTypeBidsRegex(b) = sellingType.text()
//                (PriceScraperAuction.AUCTION, Some(b.toInt))
//              })
//
//              if ( auctionId.length > 0 && auctionUrl.length > 0 && auctionTitle.length > 0 && thumbUrl.length > 0 && largeUrl.length > 0 ) {
//                (getItemPrice(itemPrice), auctionTypeAndNrBids) match {
//                  case (Success(priceScraperItemPrice), Success((auctionType, nrBids))) =>
//                    extractAuction(elementsIterator, priceScraperAuctions :+ PriceScraperAuction(auctionId, website, auctionUrl, auctionTitle, auctionType, nrBids, thumbUrl, largeUrl, priceScraperItemPrice))
//
//                  case (Failure(f1), Failure(f2)) =>
//                    Logger.error(s"Auction $auctionId failed to process itemPrice and auctionType/nrBids, skipping ...", f1)
//                    Logger.error(s"Auction $auctionId failed to process itemPrice and auctionType/nrBids, skipping ...", f2)
//                    extractAuction(elementsIterator, priceScraperAuctions)
//
//                  case (Failure(f1), Success(_)) =>
//                    Logger.error(s"Auction $auctionId failed to process itemPrice $itemPrice, skipping ...", f1)
//                    extractAuction(elementsIterator, priceScraperAuctions)
//
//                  case (Success(_), Failure(f2)) =>
//                    Logger.error(s"Auction $auctionId failed to process auctionType/nrBids $sellingType, skipping ...", f2)
//                    extractAuction(elementsIterator, priceScraperAuctions)
//                }
//              } else {
//                Logger.info(s"Auction $auctionId is missing some informations, skipping ...")
//                extractAuction(elementsIterator, priceScraperAuctions)
//              }
//            }
//
//          case false =>
//            priceScraperAuctions
//        }
//      }
//
//      extractAuction(Jsoup.parse(htmlContent).select(".item-gallery").iterator())
//
//  }

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