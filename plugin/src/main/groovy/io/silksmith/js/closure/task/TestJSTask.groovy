package io.silksmith.js.closure.task

import io.silksmith.SourceLookupService
import io.silksmith.development.server.WorkspaceServer
import io.silksmith.source.WebSourceSet

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

import org.eclipse.jetty.server.Handler
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction
import org.gradle.logging.StyledTextOutput
import org.gradle.logging.StyledTextOutputFactory
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.ui.ExpectedCondition
import org.openqa.selenium.support.ui.WebDriverWait

import com.sun.nio.file.SensitivityWatchEventModifier

class TestJSTask extends DefaultTask {

	def jsExecution = "return window.silksmith.results;"

	def symbols = [
		"ok": '✓',
		"err": '✖',
		"dot": '․'
	]

	SourceLookupService sourceLookupService
	WebSourceSet testSourceSet

	@Lazy
	WorkspaceServer server = {
		Configuration configuration = project.configurations[testSourceSet.configurationName]
		[
			project            : project,
			sourceSet          : testSourceSet,
			configuration      : configuration,
			port : 10102,

			sourceLookupService: sourceLookupService
		]
	}()

	def handler(Handler handler) {
		def s = server
		server.handler(handler)
	}

	@TaskAction
	def test() {

		def drivers = []
		def ok = false

		try {
			boolean watch = project.hasProperty('watch')
			boolean firefox = project.hasProperty('firefox')
			boolean chrome = project.hasProperty('chrome')
			boolean sauce = project.hasProperty('sauce')


			server.start()

			if (!firefox && !chrome && !sauce) {
				if ("true".equals(System.getenv('CI'))
				|| "true".equals(System.getenv('TRAVIS'))
				|| "true".equals(System.getenv('CONTINUOUS_INTEGRATION'))) {
					sauce = true
				} else {
					firefox = true
				}
			}

			if (sauce) {
				String sauceUsername = project.hasProperty('sauceUsername') ? project.property("sauceUsername") : System.getenv('SAUCE_USERNAME')
				String sauceAccessKey = project.hasProperty('sauceAccessKey') ? project.property("sauceAccessKey") : System.getenv('SAUCE_ACCESS_KEY')
				String sauceTunnelIdentifier = System.getenv('TRAVIS_JOB_NUMBER')
				String sauceBuildIdentifier = System.getenv('TRAVIS_BUILD_NUMBER')

				def capabilities = DesiredCapabilities.chrome()
				capabilities.setCapability("platform", "OS X 10.10")
				capabilities.setCapability("version", "40.0")
				if (sauceTunnelIdentifier) {
					capabilities.setCapability("tunnel-identifier", sauceTunnelIdentifier)
				}
				if (sauceBuildIdentifier) {
					capabilities.setCapability("build", sauceBuildIdentifier)
				}
				drivers << new RemoteWebDriver(new URL("http://$sauceUsername:$sauceAccessKey@localhost:4445/wd/hub"), capabilities)
			}
			if (firefox) {
				drivers << new FirefoxDriver()
			}
			if (chrome) {
				WebDriver chromeDriver
				String chromeDriverUrl = project.hasProperty('chromeDriverUrl') ? project.property("chromeDriverUrl") : null
				String chromeDriverExe = project.hasProperty('chromeDriverExe') ? project.property("chromeDriverExe") : null
				String chromeBin = project.hasProperty('chromeBin') ? project.property("chromeBin") : null

				ChromeOptions options = new ChromeOptions()
				if (chromeBin != null) {
					def chromeBinFile = new File(chromeBin)
					if (chromeBinFile.exists()) {
						options.setBinary(chromeBinFile)
					} else {
						logger.warn("Chrome binary not found at $chromeBinFile. Using default location")
					}
				}

				if (chromeDriverUrl) {
					// use existing chrome driver server
					def capabilities = DesiredCapabilities.chrome()
					capabilities.setCapability(ChromeOptions.CAPABILITY, options)
					chromeDriver = new RemoteWebDriver(new URL(chromeDriverUrl), capabilities)
				} else if (chromeDriverExe) {
					// auto start chrome driver server
					def driverFile = new File(chromeDriverExe)
					if (driverFile.exists()) {
						def driverService = new ChromeDriverService.Builder().usingAnyFreePort().usingDriverExecutable(driverFile).build()
						chromeDriver = new ChromeDriver(driverService, options)
					} else {
						logger.warn("Chrome driver executable file not found at $chromeDriverExe.")
					}
				} else if (System.hasProperty(ChromeDriverService.CHROME_DRIVER_EXE_PROPERTY)) {
					// auto start chrome driver server with exe set in system properties
					chromeDriver = new ChromeDriver(options)
				}

				if (chromeDriver != null) {
					drivers << chromeDriver
				} else {
					logger.warn("No chrome driver executable found. Download it and set its location via '-PchromeDriverExe=/Applications/chromedriver' or pass the URL of a running driver server via '-PchromeDriverUrl=http://localhost:9515'.")
					logger.warn("Alternatively set the system property 'webdriver.chrome.driver' to the location of the executable.")
					logger.warn("Download URL for the Chrome driver executable: http://chromedriver.storage.googleapis.com")
				}
			}

			if (drivers.empty) {
				logger.error("No drivers available. Specify one or more by adding '-Pfirefox' or '-Pchrome'")
				return
			}

			drivers.each { it.get("${server.server.URI}TEST/MOCHA") }

			if (watch) {
				def keysAndPath = [:]

				WatchService watchService = FileSystems.getDefault().newWatchService()
				testSourceSet.js.srcDirs.collect({ File srcDirFile ->

					if (srcDirFile.isDirectory()) {
						def dirs = [srcDirFile]
						srcDirFile.eachDirRecurse dirs.&add
						return dirs
					}
				}).grep().flatten().collect({
					Paths.get(it.toURI())
				}).each({ Path srcDirPath ->

					srcDirPath.register(
							watchService,
							[
								StandardWatchEventKinds.ENTRY_MODIFY,
								StandardWatchEventKinds.ENTRY_DELETE,
								StandardWatchEventKinds.ENTRY_CREATE
							] as WatchEvent.Kind[], SensitivityWatchEventModifier.HIGH
							)
				})

				def th = Thread.start {
					while (true) {
						logger.lifecycle("Watching")
						WatchKey key = watchService.take()

						logger.lifecycle("Files changed")

						boolean refresh = false

						//Poll all the events queued for the key
						for (WatchEvent<?> event : key.pollEvents()) {
							WatchEvent.Kind kind = event.kind()
							if (kind == StandardWatchEventKinds.OVERFLOW) {
								continue
							}
							refresh = true
						}

						if (refresh) {
							logger.lifecycle("Refreshing")
							drivers.each { it.navigate().refresh() }
							logger.lifecycle("Refreshed")
						}

						//reset is invoked to put the key back to ready state
						boolean valid = key.reset()
						//If the key is invalid, just exit.
						if (!valid) {
							logger.warn("$key is invalid, ending watch")
							break
						}

						sleep(200)
					}
				}
				th.join()

			} else {
				ok = drivers.collect({ executeTestInBrowser((WebDriver) it) }).every({ it })
			}

		} catch (Exception e) {
			logger.error("An error occured while executing tests", e)
		} finally {
			drivers.each { it.quit() }
			server.stop()
		}

		if (!ok) {
			throw new GradleException("Some tests did not pass")
		}
	}

	def executeTestInBrowser(WebDriver driver) {
		def condition = { WebDriver d ->
			JavascriptExecutor jsExec = d as JavascriptExecutor
			def results = jsExec.executeScript(jsExecution)
			return results.complete
		} as ExpectedCondition<Boolean>
		//println ((JavascriptExecutor)driver).executeScript()
		(new WebDriverWait(driver, 1000)).until(condition)


		JavascriptExecutor jsExec = driver as JavascriptExecutor
		def results = jsExec.executeScript(jsExecution)

		// stats: {suites: 0, tests: 0, passes: 0, pending: 0, failures: 0};
		boolean ok = results.stats.failures == 0

		def out = services.get(StyledTextOutputFactory).create(getClass())
		if (ok) {
			out.withStyle(StyledTextOutput.Style.Success).println("$symbols.ok $results.stats.passes tests completed")
			if (results.stats.pending > 0) {
				out.withStyle(StyledTextOutput.Style.Success).println("$symbols.dot $results.stats.pending tests pending")
			}
		} else {
			out.withStyle(StyledTextOutput.Style.Failure).println("$symbols.err $results.stats.failures of $results.stats.tests tests failed:")
			out.println("")
			results.failures.each {
				out.withStyle(StyledTextOutput.Style.Normal).println("$it.fullTitle: $it.err.message")
				out.withStyle(StyledTextOutput.Style.Normal).println("$it.err.stack")
				out.println("")
			}
		}
		return ok
	}
}

