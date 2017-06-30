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
                               auctionTitle: String,
                               auctionType: String,
                               nrBids: Option[Int],
                               thumbnailUrl: String,
                               largeUrl: String,
                               itemPrice: PriceScraperItemPrice,
                               startedAt: Option[Instant] = None,
                               soldAt: Option[Instant] = None,
                               visitCount: Option[Int] = None,
                               createdAt: Instant = Instant.now(),
                               checkedAt: Option[Instant] = None,
                               checkedStatus: Option[Int] = None
                              )

object PriceScraperAuction {
  val AUCTION = "A"
  val FIXED_PRICE = "F"
}