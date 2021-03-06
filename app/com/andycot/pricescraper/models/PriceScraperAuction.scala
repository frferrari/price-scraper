package com.andycot.pricescraper.models

import java.time.Instant

import akka.http.scaladsl.model.Uri
import org.bson.codecs.configuration.CodecProvider
import org.mongodb.scala.bson.codecs.Macros

/**
  * Created by Francois FERRARI on 12/06/2017
  */
case class PriceScraperAuction(auctionId: String,
                               website: String,
                               auctionUrl: String,
                               thumbnailUrl: String,
                               largeUrl: String,
                               auctionTitle: String,
                               auctionType: String,
                               familyId: Int,
                               areaId: Option[Int],
                               topicId: Option[Int],
                               yearRange: Option[PriceScraperYearRange],
                               options: Seq[PriceScraperOption],
                               offerCount: Option[Int],
                               sellingPrice: PriceScraperAuctionPrice,
                               version: String = PriceScraperAuction.getCurrentVersion,
                               visitCount: Option[Int] = None,
                               startedAt: Option[Instant] = None,
                               soldAt: Option[Instant] = None,
                               createdAt: Instant = Instant.now(),
                               checkedAt: Option[Instant] = None,
                               checkedStatus: Option[Int] = None
                              )

object PriceScraperAuction {
  val AUCTION = "A"
  val FIXED_PRICE = "F"

  val FAMILY_STAMP = 1

  private val versions = Seq(
    ("1.0", "initial version")
  )

  def getCurrentVersion: String = versions.last._1
}