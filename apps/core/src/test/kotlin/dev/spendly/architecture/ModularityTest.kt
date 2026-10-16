package dev.spendly.architecture

import dev.spendly.SpendlyBackendApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Module boundaries (modular monolith). Each top-level package under dev.spendly is a module; its root
 * package is the public API and subpackages are internal. Fails on access to another module's internals
 * and on dependency cycles.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(SpendlyBackendApplication::class.java)

    @Test
    fun `modules respect their boundaries`() {
        modules.verify()
    }

    @Test
    fun `write module documentation`() {
        Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml()
    }
}
