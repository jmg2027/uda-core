package udacore

import udacore.core.design.shared._

/** Core domain configurations (ADR-019 v0 reference point).
  *
  * Only manages Core domain parameters. Backend and frontend defaults live in
  * their own domains (BackendParams / FrontendParams defaults are the v0
  * reference values).
  */
object DefaultCoreConfig {

  /** The v0 reference product point (retire stream off). */
  val default = CoreParams()

  /** Fast directed-test build: shorter boot, debug features on. */
  val minimal = CoreParams(
    priv = CorePrivateParams(bootCycles = 4, debugFeatures = true)
  )
}
