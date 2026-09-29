package acr.browser.lightning.browser.tab.bundle.storage

import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.di.FilesDir
import acr.browser.lightning.log.Logger
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.os.Bundle
import android.os.Parcel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Reads and writes bundles to and from storage.
 */
class BundleWriter @AssistedInject constructor(
    private val coroutineDispatchers: CoroutineDispatchers,
    private val logger: Logger,
    @FilesDir private val filesDir: ThreadSafeFileProvider,
    @Assisted private val bundleFileName: String
) {

    @AssistedFactory
    interface Factory {
        fun create(bundleFileName: String): BundleWriter
    }

    /**
     * Writes a bundle to persistent storage in the files directory using the specified file name.
     *
     * 写入采用「临时文件 + fsync + 原子 rename」：进程在写入中途被杀（adb install /
     * force-stop / 低内存）时不会留下损坏的半截文件，最坏情况是保留上一次完整快照。
     *
     * @param bundle the bundle to store in persistent storage.
     */
    suspend fun writeToStorage(bundle: Bundle?) = withContext(coroutineDispatchers.io) {
        val dir = filesDir.file()
        val outputFile = File(dir, bundleFileName)
        val tempFile = File(dir, "$bundleFileName.tmp")
        try {
            FileOutputStream(tempFile).use { outputStream ->
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(bundle)
                    outputStream.write(parcel.marshall())
                    outputStream.flush()
                    outputStream.fd.sync()
                } finally {
                    parcel.recycle()
                }
            }
            if (!tempFile.renameTo(outputFile)) {
                // rename 失败（极少数文件系统），退化为先删后改名的非原子路径
                outputFile.delete()
                if (!tempFile.renameTo(outputFile)) {
                    logger.log(TAG, "Unable to replace bundle file after write")
                    tempFile.delete()
                }
            }
        } catch (e: IOException) {
            logger.log(TAG, "Unable to write bundle to storage", e)
            tempFile.delete()
        }
    }

    /**
     * Use this method to delete the bundle with the specified name.
     */
    suspend fun deleteInStorage() = withContext(coroutineDispatchers.io) {
        val outputFile = File(filesDir.file(), bundleFileName)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val tempFile = File(filesDir.file(), "$bundleFileName.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    /**
     * Reads a bundle from the file with the specified name in the persistent storage files
     * directory.
     *
     * @return a valid Bundle loaded using the system class loader or null if the method was unable
     * to read the Bundle from storage.
     */
    suspend fun readFromStorage(): Bundle? = withContext(coroutineDispatchers.io) {
        val inputFile = File(filesDir.file(), bundleFileName)
        try {
            FileInputStream(inputFile).use { inputStream ->
                val parcel = Parcel.obtain()
                val data = ByteArray(inputStream.channel.size().toInt())

                inputStream.read(data, 0, data.size)
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                val out = parcel.readBundle(ClassLoader.getSystemClassLoader())
                out!!.putAll(out)
                parcel.recycle()
                return@withContext out
            }
        } catch (e: FileNotFoundException) {
            logger.log(TAG, "Unable to read bundle from storage", e)
        } catch (e: IOException) {
            logger.log(TAG, "Unable to read bundle from storage", e)
        }
        return@withContext null
    }

    private companion object {
        private const val TAG = "BundleWriter"
    }
}
