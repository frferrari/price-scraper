package com.andycot.pricescraper.models

import org.bson.codecs.configuration.CodecProvider
import org.mongodb.scala.bson.codecs.Macros

/**
  * Created by Francois FERRARI on 10/06/2017
  */
case class PriceScraperUrl(website: String, url: String)