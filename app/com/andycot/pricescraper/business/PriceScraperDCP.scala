package com.andycot.pricescraper.business

import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util

import akka.http.scaladsl.model.Uri
import com.andycot.pricescraper.models._
import com.andycot.pricescraper.utils.PriceScraperUrlManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.Logger

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * Created by Francois FERRARI on 12/06/2017
  */

object PriceScraperDCP extends PriceScraperExtractor {
  /*
   * See UTF-8 tables
   *
   * http://utf8-chartable.de/unicode-utf8-table.pl?start=8320&number=128&names=-
   * http://www.utf8-chartable.de/
   */
  val currencyMap = Map(
    "\u00a0\u20ac" -> "EUR", // 00a0 = space    20ac = €
    "\u00a0\u20a4" -> "GBP"
  )

  val sellingTypeFixedPrice = "Prix fixe"
  val sellingTypeBidsRegex = """([0-9]+).*""".r // Something like "1&nbsp;offre" or "3&nbsp;offres"

  // Matches a price string like this one "6,00 €" or "~ 6,00 €"
  val priceCurrencyRegex = """.*([0-9,.]+)(.*)""".r

  val auctionIdRegex = """item-([0-9]+)""".r

  override def extractAuctions(priceScraperWebsite: PriceScraperWebsite, url: String, htmlContent: String): Future[Seq[PriceScraperAuction]] = Future {
    @tailrec def extractAuction(elementsIterator: util.Iterator[Element], priceScraperAuctions: Seq[PriceScraperAuction] = Nil): Seq[PriceScraperAuction] = {
      elementsIterator.hasNext match {
        case true =>
          val element: Element = elementsIterator.next()
          val imageContainer = element.select("div.item-content > div.image-container")
          val auctionIdRegex(auctionId) = element.attr("id")

          if (imageContainer.select("a.img-view").hasClass("default-thumb")) {
            Logger.info(s"PriceCrawlerDCP.extractAuction auction $auctionId has no picture, skipping ...")
            extractAuction(elementsIterator, priceScraperAuctions)
          } else {

            val auctionId = imageContainer.select("a.img-view").attr("data-item-id").trim
            val largeUrl = element.select("a.img-view").attr("href").trim
            //
            val imgThumbElement = imageContainer.select("img.image-thumb")
            val auctionTitle = imgThumbElement.attr("alt").trim
            val thumbUrl = imgThumbElement.attr("data-original").trim

            //
            val itemFooterElement = element.select("div.item-content > div.item-footer")
            val auctionUri = Uri(itemFooterElement.select("a.item-link").attr("href").trim).resolvedAgainst(priceScraperWebsite.baseUrl)
            val itemPrice = itemFooterElement.select(".item-price").text().trim

            val sellingType = itemFooterElement.select("div.selling-type-right > span.selling-type-text")
            val auctionTypeAndNrBids = Try(if (sellingType.text().contains(sellingTypeFixedPrice)) {
              (PriceScraperAuction.FIXED_PRICE, None)
            } else {
              val sellingTypeBidsRegex(b) = sellingType.text()
              (PriceScraperAuction.AUCTION, Some(b.toInt))
            })

            if ( auctionId.length > 0 && auctionTitle.length > 0 && thumbUrl.length > 0 && largeUrl.length > 0 ) {
              (getItemPrice(itemPrice), auctionTypeAndNrBids) match {
                case (Success(priceScraperItemPrice), Success((auctionType, nrBids))) =>
                  extractAuction(elementsIterator, priceScraperAuctions :+ PriceScraperAuction(auctionId, priceScraperWebsite.website, auctionUri.toString, auctionTitle, auctionType, nrBids, thumbUrl, largeUrl, priceScraperItemPrice))

                case (Failure(f1), Failure(f2)) =>
                  Logger.error(s"Auction $auctionId failed to process itemPrice and auctionType/nrBids, skipping ...", f1)
                  Logger.error(s"Auction $auctionId failed to process itemPrice and auctionType/nrBids, skipping ...", f2)
                  extractAuction(elementsIterator, priceScraperAuctions)

                case (Failure(f1), Success(_)) =>
                  Logger.error(s"Auction $auctionId failed to process itemPrice $itemPrice, skipping ...", f1)
                  extractAuction(elementsIterator, priceScraperAuctions)

                case (Success(_), Failure(f2)) =>
                  Logger.error(s"Auction $auctionId failed to process auctionType/nrBids $sellingType, skipping ...", f2)
                  extractAuction(elementsIterator, priceScraperAuctions)
              }
            } else {
              Logger.info(s"Auction $auctionId is missing some informations, skipping ...")
              extractAuction(elementsIterator, priceScraperAuctions)
            }
          }

        case false =>
          priceScraperAuctions
      }
    }

    extractAuction(Jsoup.parse(htmlContent, url).select(".item-gallery").iterator())
  }

  /**
    *
    * @param priceScraperUrl
    * @param priceScraperWebsites
    * @param htmlContent
    * @return
    */
  override def getPagedUrls(priceScraperUrl: PriceScraperUrl, priceScraperWebsites: Seq[PriceScraperWebsite], htmlContent: String): Seq[PriceScraperUrl] = {
    val pageNumberRegex = """.*<a class="pag-number.*" href=".*">([0-9]+)</a>.*""".r

    pageNumberRegex.findAllIn(htmlContent).matchData.flatMap(_.subgroups).toList.lastOption match {
      case Some(lastPageNumber) =>
        PriceScraperUrlManager.generateAllUrls(priceScraperUrl, priceScraperWebsites, lastPageNumber.toInt)

      case None =>
        // Case when there's only one page of auctions for this category
        PriceScraperUrlManager.generateAllUrls(priceScraperUrl, priceScraperWebsites, 1)
    }
  }

  /**
    *
    * @param priceWithCurrency
    * @return
    */
  override def getItemPrice(priceWithCurrency: String): Try[PriceScraperItemPrice] = Try {

    val priceCurrencyRegex(price, externalCurrency) = priceWithCurrency

    mapToInternalCurrency(externalCurrency) match {
      case Some(internalCurrency) =>
        // An external price is a string like "120,00" or "4 950,00"
        PriceScraperItemPrice(BigDecimal(price.replace(",", ".").replace(" ", "")), internalCurrency)

      case _ =>
        Logger.error(s"PriceCrawlerDCP.getItemPrice Couldn't parse currency $externalCurrency")
        throw new Exception(s"PriceCrawlerDCP.getItemPrice Couldn't parse currency $externalCurrency")
    }
  }

  /**
    *
    * @param externalCurrency
    * @return
    */
  override def mapToInternalCurrency(externalCurrency: String): Option[String] = currencyMap.get(externalCurrency.trim)

  /**
    *
    * @param priceScraperAuction
    * @param htmlContent
    * @return
    */
  override def extractAuctionInformations(priceScraperAuction: PriceScraperAuction, htmlContent: String): PriceScraperAuction = Try {
    val html = Jsoup.parse(htmlContent).select("body")

    // Début de la vente : jeudi 22 juin 2017 à 13:55 15 visites
    val startedAtInfo = html.select(".info-view").text.replace("&nbsp;", " ").trim
    Logger.info(s"startedAtInfo [$startedAtInfo]")
    val startedAtRegex = ".*vente[^A-Za-z]+([A-Za-z]+ [0-9]+ [A-Za-z]+ [0-9]+).*([0-9]{2}:[0-9]{2}) ([0-9]+) visites".r
    val startedAtRegex(startedAtDay, startedAtTime, visitCount) = startedAtInfo
    val startedAtText = s"$startedAtDay $startedAtTime"
    val startedAt = toInstant(startedAtText)

    // Vendue le vendredi 23 juin 2017 20:06
    val soldAtInfo = html.select(".alert-info").text().replaceAll("&nbsp;", " ").trim
    val soldAtRegex = "Vendue le ([A-ZA-z]+ [0-9]+ [A-ZA-z]+ [0-9]{4} [0-9]{2}:[0-9]{2})".r
    val soldAtRegex(soldAtText) = soldAtInfo

    val soldAt = toInstant(soldAtText)

    (startedAt, soldAt, visitCount.toInt)
  } match {
    case Success((startedAt, soldAt, visitCount)) =>
      priceScraperAuction.copy(startedAt = Some(startedAt), soldAt = Some(soldAt), visitCount = Some(visitCount))

    case Failure(f) =>
      Logger.error(s"extractAuctionInformations: Extraction error for auction ${priceScraperAuction.auctionId}", f)
      priceScraperAuction
  }

  /**
    * Converts a date to an Instant
    * Exemple of date: vendredi 23 juin 2017 20:06
    *
    * @param date
    * @return
    */
  private def toInstant(date: String): Instant = {
    LocalDateTime.parse(date, DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm").withLocale(new java.util.Locale("fr"))).toInstant(ZoneOffset.UTC)
  }
}
