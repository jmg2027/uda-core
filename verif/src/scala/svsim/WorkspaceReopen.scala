package svsim

/** Reopen a previously compiled svsim working directory as a Simulation, skipping the
 *  verilator + make step entirely. Correct only when the workspace still holds the exact
 *  sources the binary was built from - the caller (chisel3.simulator.CachedSimulator) guards
 *  that with a content hash over the elaborated circuit. Lives in package svsim to reach the
 *  private[svsim] Simulation constructor; everything else it does mirrors the tail of
 *  Workspace.compile (parameter generation is a pure function, so regenerating it against the
 *  same settings reproduces the invocation the binary was built for). */
object WorkspaceReopen {
  def simulation[B <: Backend](
    backend:               B
  )(workspaceAbsolutePath: String,
    workingDirectoryTag:   String,
    commonSettings:        CommonCompilationSettings,
    backendSettings:       backend.CompilationSettings,
    moduleInfo:            ModuleInfo
  ): Simulation = {
    val workingDirectoryPath = s"$workspaceAbsolutePath/workdir-$workingDirectoryTag"
    val parameters = backend.generateParameters(
      outputBinaryName = "simulation",
      topModuleName = Workspace.testbenchModuleName,
      additionalHeaderPaths = Seq(workingDirectoryPath),
      commonSettings = commonSettings,
      backendSpecificSettings = backendSettings
    )
    val simulationEnvironment = Seq(
      "SVSIM_SIMULATION_LOG" -> s"$workingDirectoryPath/simulation-log.txt",
      "SVSIM_SIMULATION_TRACE" -> s"$workingDirectoryPath/trace"
    ) ++ parameters.simulationInvocation.environment
    new Simulation(
      executableName = "simulation",
      settings = Simulation.Settings(
        customWorkingDirectory = None,
        arguments = parameters.simulationInvocation.arguments,
        environment = simulationEnvironment.toMap
      ),
      workingDirectoryPath = workingDirectoryPath,
      moduleInfo = moduleInfo
    )
  }
}
