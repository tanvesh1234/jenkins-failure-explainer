package com.cloudbees.groovy.cps

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Test-only stand-in for Jenkins' @NonCPS annotation, so the library compiles and runs outside
 * Jenkins (unit tests and the local CLI). Jenkins provides the real one at runtime; this
 * directory is never loaded by Jenkins because shared libraries only read src/, vars/, resources/.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.CONSTRUCTOR])
@interface NonCPS {
}
