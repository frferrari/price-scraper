package com.andycot.pricescraper.models

import org.bson.codecs.configuration.CodecProvider
import org.mongodb.scala.bson.codecs.Macros

/**
  * Created by Francois FERRARI on 19/06/2017
  */
case class PriceScraperQueryParameter(name: String, value: String)
