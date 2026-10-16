package dev.spendly.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Shared infrastructure (config, crypto, privacy, messaging, outbox, web errors, observability). An OPEN module:
 * every module may use all of its subpackages, and it must not depend on any feature module.
 */
@PackageInfo
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
class SharedModule
