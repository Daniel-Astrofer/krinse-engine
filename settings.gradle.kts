rootProject.name = "kerosene-kfe"

fun resolveCompositeBuild(environmentVariable: String, vararg candidates: String): String =
    providers.environmentVariable(environmentVariable).orNull?.takeIf { it.isNotBlank() }
        ?: candidates.firstOrNull { file(it).isDirectory }
        ?: candidates.first()

val contractsDirectory = resolveCompositeBuild(
    "KEROSENE_CONTRACTS_DIR",
    "../contracts",
    "../kerosene-contracts",
    "../../platform/kerosene-contracts",
    "../../platform/contracts",
)

includeBuild(contractsDirectory) {
    dependencySubstitution {
        substitute(module("io.kerosene.contracts:kerosene-contracts"))
            .using(project(":"))
    }
}

val sharedDirectory = resolveCompositeBuild(
    "KEROSENE_SHARED_DIR",
    "../shared",
    "../kerosene-shared",
    "../../platform/kerosene-shared",
    "../../platform/shared",
)

includeBuild(sharedDirectory) {
    dependencySubstitution {
        substitute(module("kerosene:kerosene-shared"))
            .using(project(":"))
    }
}
