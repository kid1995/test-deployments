#!/usr/bin/env groovy
package de.signaliduna

/**
 * Exception used by change automation for user defined validations or checks.
 */
class BusinessException extends Exception
{
      public BusinessException(String message)
      {
         super(message);
      }
 }