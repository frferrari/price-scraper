package com.andycot.pricescraper.business

import java.time.LocalDate

import com.andycot.pricescraper.models._

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by Francois FERRARI on 12/06/2017
  */
trait PriceScraperExtractor {
  def extractAuctions(priceScraperWebsite: PriceScraperWebsite, priceScraperUrl: PriceScraperUrl, htmlContent: String): Future[Seq[PriceScraperAuction]]

  def extractAuctionInformations(priceScraperAuction: PriceScraperAuction, htmlContent: String): PriceScraperAuction

  def getPagedUrls(priceCrawlerUrl: PriceScraperUrl, priceCrawlerWebsites: Seq[PriceScraperWebsite], htmlContent: String): Seq[PriceScraperUrl]

  def getAuctionPrice(priceWithCurrency: String): Try[PriceScraperAuctionPrice]

  def mapToInternalCurrency(externalCurrency: String): Option[String]

  /**
    * Tries to guess the year from an auction title
    *
    * @param string The string from where to extract the title
    * @param maybePriceScraperYearRange The range of valid year values
    * @return
    */
  def guessYear(string: String, maybePriceScraperYearRange: Option[PriceScraperYearRange]): Option[PriceScraperYearRange] = {
    maybePriceScraperYearRange match {
      case Some(priceScraperYearRange) if priceScraperYearRange.from == priceScraperYearRange.to =>
        Some(priceScraperYearRange)

      case Some(priceScraperYearRange) =>
        guessValidYear(string, validYearBounded(priceScraperYearRange))

      case None =>
        guessValidYear(string, validYearGeneral)
    }
  }

  /**
    *
    * @param string The string from where to extract the title
    * @param validYear A function that checks the year validity
    * @return
    */
  def guessValidYear(string: String, validYear: Int => Boolean): Option[PriceScraperYearRange] = Try {
    val yearRegex = "\\b(17|18|19|20)\\d{2}\\b".r

    yearRegex.findAllIn(string).toList.map(_.toInt).filter(validYear) match {
      case years if years.size == 1 =>
        PriceScraperYearRange(years.head, years.head)

      case years if years.size > 1 =>
        throw new Exception("Too many values to choose from")

      case Nil =>
        throw new Exception("No valid year found")
    }
  }.toOption

  /**
    * Checks if a given year is in the common year range values
    * @param year The year to check for validity
    * @return
    */
  def validYearGeneral(year: Int): Boolean = {
    // TODO Move to config file or make it dependent on the familyId (postcards may have a minimum year lower then stamps)
    year >= 1700 && year <= LocalDate.now().plusYears(1).getYear
  }

  /**
    * Checks if a given year is in the range of a given range values
    * @param priceScraperYearRange The range of allowed values for the given year
    * @param year Given year to check for validity
    * @return
    */
  def validYearBounded(priceScraperYearRange: PriceScraperYearRange)(year: Int): Boolean = {
    year >= priceScraperYearRange.from && year <= priceScraperYearRange.to
  }
}
