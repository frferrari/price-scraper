package com.andycot.pricescraper.business

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

  override def extractAuctions(priceScraperWebsite: PriceScraperWebsite, uri: Uri, htmlContent: String): Future[Seq[PriceScraperAuction]] = Future {
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
            val auctionUrl = itemFooterElement.select("a.item-link").attr("href").trim
            val itemPrice = itemFooterElement.select(".item-price").text().trim

            val sellingType = itemFooterElement.select("div.selling-type-right > span.selling-type-text")
            val auctionTypeAndNrBids = Try(if (sellingType.text().contains(sellingTypeFixedPrice)) {
              (PriceScraperAuction.FIXED_PRICE, None)
            } else {
              val sellingTypeBidsRegex(b) = sellingType.text()
              (PriceScraperAuction.AUCTION, Some(b.toInt))
            })

            if ( auctionId.length > 0 && auctionUrl.length > 0 && auctionTitle.length > 0 && thumbUrl.length > 0 && largeUrl.length > 0 ) {
              (getItemPrice(itemPrice), auctionTypeAndNrBids) match {
                case (Success(priceScraperItemPrice), Success((auctionType, nrBids))) =>
                  extractAuction(elementsIterator, priceScraperAuctions :+ PriceScraperAuction(auctionId, priceScraperWebsite.website, auctionUrl, auctionTitle, auctionType, nrBids, thumbUrl, largeUrl, priceScraperItemPrice))

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

    extractAuction(Jsoup.parse(htmlContent, uri.toString).select(".item-gallery").iterator())
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
}
