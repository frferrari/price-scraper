package com.andycot.pricescraper.business

import com.andycot.pricescraper.models._
import com.andycot.pricescraper.services.PriceScraperUrlService

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by Francois FERRARI on 12/06/2017
  */
trait PriceScraperExtractor {
  def extractAuctions(website: String, htmlContent: String): Future[Seq[PriceScraperAuction]]

  def getPagedUrls(priceCrawlerUrl: PriceScraperUrl, priceCrawlerWebsites: Seq[PriceScraperWebsite], htmlContent: String)(implicit priceCrawlerUrlService: PriceScraperUrlService): Seq[String]

  def getItemPrice(priceWithCurrency: String): Try[PriceScraperItemPrice]

  def mapToInternalCurrency(externalCurrency: String): Option[String]
}
