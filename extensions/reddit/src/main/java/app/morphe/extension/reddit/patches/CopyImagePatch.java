/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import com.reddit.fullbleedplayer.composables.IconType;
import com.reddit.fullbleedplayer.ui.ActionMenuItemUiState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
public final class CopyImagePatch {

    /**
     * Paths declared by the app FileProvider in res/xml/provider_paths.xml
     * {@code <cache-path name="internal_cache_files" path="internal_cache_share" />}
     */
    private static final String FILE_PROVIDER_CACHE_NAME = "internal_cache_files";
    private static final String FILE_PROVIDER_CACHE_PATH = "internal_cache_share";
    private static final String CLIPBOARD_DIRECTORY = "morphe_clipboard";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 15_000;

    /**
     * Longest side of an image that is re-encoded to png.
     */
    private static final int MAX_DECODED_IMAGE_SIZE = 4096;

    @Nullable
    private static volatile IconType copyImageIconType;

    /**
     * Media page of the menu currently being built.
     * The builder method is not suspendable, so the start and the end of a build
     * always run on the same thread, while different builds can run on different threads.
     */
    private static final ThreadLocal<Object> menuMediaPage = new ThreadLocal<>();

    /**
     * Menu icon. The {@code toIcon} method is added during patching,
     * because its signature uses obfuscated types.
     */
    public static final class CopyImageIconType extends IconType {
        public CopyImageIconType(int ordinal) {
            super("MorpheCopyImage", ordinal, null);
        }
    }

    private static final class CopyImageOnClick implements Function1<Object, Object>, Serializable {
        private final String imageUrl;

        CopyImageOnClick(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        @Override
        public Object invoke(Object mediaPage) {
            copyImageToClipboard(imageUrl);
            return null;
        }

        /**
         * The menu item is a data class that compares its click function,
         * so equal clicks keep a rebuilt menu equal to the previous one.
         */
        @Override
        public boolean equals(Object obj) {
            return obj instanceof CopyImageOnClick
                    && imageUrl.equals(((CopyImageOnClick) obj).imageUrl);
        }

        @Override
        public int hashCode() {
            return imageUrl.hashCode();
        }
    }

    /**
     * Implementation is replaced during patching.
     *
     * @return Url of the currently shown image of an image post, or null if the media is not an image.
     */
    @Nullable
    private static String getImageUrl(Object mediaPage) {
        return null; // Modified during patching.
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * Called at the start of the menu items builder method.
     */
    public static void setMenuMediaPage(Object mediaPage) {
        menuMediaPage.set(mediaPage);
    }

    /**
     * Injection point.
     * Called with the result of the menu items builder method.
     */
    public static List<?> addCopyImageMenuItem(List<?> menuItems) {
        Object mediaPage = menuMediaPage.get();
        menuMediaPage.remove();

        if (!Settings.COPY_IMAGE_BUTTON.get()) {
            return menuItems;
        }

        try {
            String imageUrl = getImageUrl(mediaPage);
            if (imageUrl == null || imageUrl.isEmpty()) {
                return menuItems;
            }

            ActionMenuItemUiState copyImageItem = new ActionMenuItemUiState(
                    getCopyImageIconType(),
                    str("morphe_copy_image_menu_item"),
                    null,
                    null,
                    new CopyImageOnClick(imageUrl)
            );

            List<Object> items = new ArrayList<>(menuItems);
            items.add(getDownloadItemIndex(items) + 1, copyImageItem);
            return items;
        } catch (Exception ex) {
            Logger.printException(() -> "addCopyImageMenuItem failure", ex);
            return menuItems;
        }
    }

    /**
     * Injection point.
     *
     * @return The duplicate (copy) icon that matches the style of the original download icon.
     */
    public static int getCopyImageIconResource(int downloadIconResource) {
        try {
            String downloadName = Utils.getContext().getResources().getResourceEntryName(downloadIconResource);
            String copyName = downloadName.replace("download", "duplicate");
            int copyResource = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, copyName);
            if (copyResource != 0) {
                return copyResource;
            }
            Logger.printDebug(() -> "Could not find icon: " + copyName);
        } catch (Exception ex) {
            Logger.printException(() -> "getCopyImageIconResource failure", ex);
        }
        return downloadIconResource;
    }

    private static IconType getCopyImageIconType() {
        IconType iconType = copyImageIconType;
        if (iconType == null) {
            iconType = new CopyImageIconType(IconType.values().length);
            copyImageIconType = iconType;
        }
        return iconType;
    }

    /**
     * @return Index of the download item, or the last item if the download item is not found.
     */
    private static int getDownloadItemIndex(List<Object> items) {
        IconType download = IconType.Download;
        for (int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            for (Field field : item.getClass().getFields()) {
                if (field.getType() != IconType.class) continue;
                try {
                    if (field.get(item) == download) {
                        return i;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return items.size() - 1;
    }

    private static void copyImageToClipboard(String imageUrl) {
        Utils.runOnBackgroundThread(() -> {
            try {
                Context context = Utils.getContext();
                File imageFile = downloadImage(context, imageUrl);
                Uri uri = getFileProviderUri(context, imageFile);

                Utils.runOnMainThread(() -> {
                    try {
                        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                        clipboard.setPrimaryClip(ClipData.newUri(
                                context.getContentResolver(),
                                str("morphe_copy_image_menu_item"),
                                uri
                        ));

                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Utils.showToastShort(str("morphe_copy_image_success"));
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "Failed to set clipboard", ex);
                        Utils.showToastShort(str("morphe_copy_image_failure"));
                    }
                });
            } catch (Exception ex) {
                Logger.printInfo(() -> "Failed to copy image: " + imageUrl, ex);
                Utils.runOnMainThread(() -> Utils.showToastShort(str("morphe_copy_image_failure")));
            } catch (OutOfMemoryError error) {
                // Image is too large to buffer or decode. Fail instead of crashing the app.
                Logger.printException(() -> "Out of memory copying image: " + imageUrl, error);
                Utils.runOnMainThread(() -> Utils.showToastShort(str("morphe_copy_image_failure")));
            }
        });
    }

    private static File downloadImage(Context context, String imageUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        try {
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected response code: " + responseCode);
            }

            File imageFile;
            String extension = getImageExtension(connection.getContentType(), imageUrl);
            try (InputStream in = connection.getInputStream()) {
                if (extension != null) {
                    // Supported format, save as is.
                    imageFile = createClipboardFile(context, extension);
                    try (OutputStream out = new FileOutputStream(imageFile)) {
                        copyStream(in, out);
                    }
                } else {
                    // WebP and other formats are not supported by many apps when pasting. Convert to png.
                    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
                    copyStream(in, imageBytes);
                    Bitmap bitmap = decodeBitmap(imageBytes.toByteArray());
                    if (bitmap == null) {
                        throw new IOException("Could not decode image");
                    }
                    try {
                        imageFile = createClipboardFile(context, "png");
                        try (OutputStream out = new FileOutputStream(imageFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
            return imageFile;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * @return A new file for the copied image. Previously copied images are deleted.
     */
    private static File createClipboardFile(Context context, String extension) throws IOException {
        File directory = new File(new File(context.getCacheDir(), FILE_PROVIDER_CACHE_PATH), CLIPBOARD_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create directory: " + directory);
        }
        // Only keep the latest copied image.
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) {
            for (File oldFile : oldFiles) {
                //noinspection ResultOfMethodCallIgnored
                oldFile.delete();
            }
        }

        return new File(directory, "image_" + System.currentTimeMillis() + "." + extension);
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * Decodes the image, downsampled so the longest side is at most {@link #MAX_DECODED_IMAGE_SIZE}
     * to keep the bitmap memory bounded.
     */
    @Nullable
    private static Bitmap decodeBitmap(byte[] imageBytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

        int sampleSize = 1;
        int longestSide = Math.max(options.outWidth, options.outHeight);
        while (longestSide / sampleSize > MAX_DECODED_IMAGE_SIZE) {
            sampleSize *= 2;
        }

        options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
    }

    /**
     * @return File extension for formats that are widely supported when pasting, or null otherwise.
     */
    @Nullable
    private static String getImageExtension(@Nullable String contentType, String imageUrl) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (type.contains("jpeg") || type.contains("jpg")) return "jpg";
        if (type.contains("png")) return "png";
        if (type.contains("gif")) return "gif";
        if (!type.isEmpty() && !type.contains("octet-stream")) return null;

        String path = Uri.parse(imageUrl).getLastPathSegment();
        path = path == null ? "" : path.toLowerCase(Locale.US);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "jpg";
        if (path.endsWith(".png")) return "png";
        if (path.endsWith(".gif")) return "gif";
        return null;
    }

    /**
     * Builds the content uri the same way as {@code androidx.core.content.FileProvider}
     * does for the app file provider.
     */
    private static Uri getFileProviderUri(Context context, File file) throws IOException {
        String authority = ResourceUtils.getString("provider_authority_file");
        if (authority == null || authority.isEmpty()) {
            authority = context.getPackageName() + ".provider.file";
        }

        String rootPath = new File(context.getCacheDir(), FILE_PROVIDER_CACHE_PATH).getCanonicalPath();
        String filePath = file.getCanonicalPath();
        String relativePath = filePath.substring(rootPath.length() + 1);

        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .encodedPath(Uri.encode(FILE_PROVIDER_CACHE_NAME) + '/' + Uri.encode(relativePath, "/"))
                .build();
    }
}
