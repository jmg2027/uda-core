package framework.macros

import scala.annotation.StaticAnnotation
import framework.specs.SpecBuilder

/** Annotation used to link design elements with specification entries. */

class LocalSpec(name: Any) extends StaticAnnotation {}
