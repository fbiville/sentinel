package org.liquigraph.sentinel.github

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.liquigraph.sentinel.Addition
import org.liquigraph.sentinel.Fixtures
import org.liquigraph.sentinel.Update
import org.liquigraph.sentinel.configuration.BotPullRequestSettings
import org.liquigraph.sentinel.toVersion
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class StoredVersionServiceTest {

    private val storedBuildClient = mockk<StoredBuildClient>()
    private val neo4jVersionParser = mockk<StoredVersionParser>()
    private lateinit var service: StoredVersionService
    private lateinit var yamlParser: Yaml

    @BeforeEach
    fun `set up`() {
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        yamlParser = Yaml(options)
        service = StoredVersionService(storedBuildClient, neo4jVersionParser, BotPullRequestSettings(), yamlParser)
        val parsingResult = Result.success(listOf(StoredVersion("3.0.11".toVersion(), true), StoredVersion("3.1.7".toVersion())))
        every { neo4jVersionParser.parse(any()) } returns parsingResult
        every { storedBuildClient.fetchBuildDefinition() } returns Result.success(Fixtures.travisYml)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `update yaml with new version addition`() {
        val yaml = Fixtures.travisYml
        val content = yamlParser.load<MutableMap<String, Any>>(yaml)

        val result = service.applyChanges(
                Fixtures.travisYml,
                listOf(Addition("4.0.0".toVersion(), true),
                        Addition("5.0.0".toVersion(), false)))

        val updatedContent = yamlParser.load<MutableMap<String, Any>>(result.getOrThrow())

        val updatedEnv = updatedContent.remove("env") as Map<String, List<String>>
        val env = content.remove("env") as Map<String, List<String>>

        assertThat(updatedContent).isEqualTo(content)
        assertThat(updatedEnv["matrix"])
                .containsAll(env["matrix"])
                .contains("NEO_VERSION=4.0.0 WITH_DOCKER=true", "NEO_VERSION=5.0.0 WITH_DOCKER=false")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `updated versions should preserve order of additions`() {
        val newNonDockerizedVersion = listOf(Addition("3.0.12".toVersion(), false))

        val result = service.applyChanges(Fixtures.travisYml, newNonDockerizedVersion)

        val updatedContent = yamlParser.load<MutableMap<String, Any>>(result.getOrThrow())
        val matrixAfterUpdate = (updatedContent["env"] as Map<String, List<String>>)["matrix"]

        assertThat(matrixAfterUpdate).containsExactly(
                "NEO_VERSION=3.0.11 WITH_DOCKER=true",
                "NEO_VERSION=3.0.12 WITH_DOCKER=false",
                "NEO_VERSION=3.1.7 WITH_DOCKER=false")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `should apply updates in order`() {
        val newVersions = listOf(
                Addition("3.2.8".toVersion(), false),
                Update("3.1.7".toVersion(), "3.1.9".toVersion(), true))

        val result = service.applyChanges(Fixtures.travisYml, newVersions)

        val updatedContent = yamlParser.load<MutableMap<String, Any>>(result.getOrThrow())
        val matrixAfterUpdate = (updatedContent["env"] as Map<String, List<String>>)["matrix"]

        assertThat(matrixAfterUpdate).containsExactly(
                "NEO_VERSION=3.0.11 WITH_DOCKER=true",
                "NEO_VERSION=3.1.9 WITH_DOCKER=true",
                "NEO_VERSION=3.2.8 WITH_DOCKER=false")
    }
}
