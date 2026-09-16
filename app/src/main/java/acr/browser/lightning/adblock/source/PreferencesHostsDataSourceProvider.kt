package acr.browser.lightning.adblock.source

import acr.browser.lightning.preference.UserPreferencesDataStore
import dagger.Reusable
import javax.inject.Inject

/**
 * A [HostsDataSourceProvider] backed by [UserPreferencesDataStore].
 */
@Reusable
class PreferencesHostsDataSourceProvider @Inject constructor(
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val autoUpdateHostsDataSource: AutoUpdateHostsDataSource,
    private val assetsHostsDataSource: AssetsHostsDataSource,
    private val fileHostsDataSourceFactory: FileHostsDataSource.Factory,
    private val urlHostsDataSourceFactory: UrlHostsDataSource.Factory
) : HostsDataSourceProvider {

    override suspend fun createHostsDataSource(): HostsDataSource =
        when (val source = userPreferencesDataStore.selectedHostsSource()) {
            HostsSourceType.Default -> autoUpdateHostsDataSource
            is HostsSourceType.Local -> fileHostsDataSourceFactory.create(source.file)
            is HostsSourceType.Remote -> urlHostsDataSourceFactory.create(source.httpUrl)
        }
}
