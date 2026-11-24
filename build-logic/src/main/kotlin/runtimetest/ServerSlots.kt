package runtimetest

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Limits how many [ModRuntimeTest] servers run at once (`org.gradle.parallel` would otherwise start them all). */
abstract class ServerSlots : BuildService<BuildServiceParameters.None>
