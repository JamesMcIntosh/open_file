package com.crazecoder.openfile;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import com.crazecoder.openfile.utils.JsonUtil;
import com.crazecoder.openfile.utils.MapUtil;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;


/**
 * OpenFilePlugin
 */
public class OpenFilePlugin implements FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener {

    private static final int REQUEST_CODE = 33432;
    private static final int RESULT_CODE = 0x12;

    private static final String TYPE_PREFIX_IMAGE = "image/";
    private static final String TYPE_PREFIX_VIDEO = "video/";
    private static final String TYPE_PREFIX_AUDIO = "audio/";

    private Context context;
    private Activity activity;
    private MethodChannel channel;


    private Result result;
    private String filePath;
    private String typeString;

    private boolean isResultSubmitted = false;

    @Deprecated
    public static void registerWith(PluginRegistry.Registrar registrar) {
        OpenFilePlugin plugin = new OpenFilePlugin();
        plugin.activity = registrar.activity();
        plugin.context = registrar.context();
        plugin.channel = new MethodChannel(registrar.messenger(), "open_file");
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        isResultSubmitted = false;
        if (call.method.equals("open_file")) {
            this.result = result;
            filePath = call.argument("file_path");
            if (call.hasArgument("type") && call.argument("type") != null) {
                typeString = call.argument("type");
            } else {
                typeString = getFileMimeType(filePath);
            }
            if (!isFileAvailable()) {
                return;
            }
            if (pathRequiresPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        if (!isMediaStorePath() && !Environment.isExternalStorageManager()) {
                            result(-3, "Permission denied: android.Manifest.permission.MANAGE_EXTERNAL_STORAGE");
                            return;
                        }
                    }
                }
                if (checkPermissions()) {
                    startActivity();
                }
            } else {
                startActivity();
            }
        } else {
            result.notImplemented();
            isResultSubmitted = true;
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (typeStartsWith(TYPE_PREFIX_IMAGE, typeString)) {
                if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES) && !Environment.isExternalStorageManager()) {
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_CODE);
                    return false;
                }
            } else if (typeStartsWith(TYPE_PREFIX_VIDEO, typeString)) {
                if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO) && !Environment.isExternalStorageManager()) {
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_CODE);
                    return false;
                }
            } else if (typeStartsWith(TYPE_PREFIX_AUDIO, typeString)) {
                if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO) && !Environment.isExternalStorageManager()) {
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_CODE);
                    return false;
                }
            }
        } else {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    private boolean typeStartsWith(String prefix, String fileType) {
        return fileType != null && fileType.startsWith(prefix);
    }

    private boolean isMediaStorePath() {
        boolean isMediaStorePath = false;
        final String[] mediaStorePath = {
                "/DCIM/",
                "/Pictures/",
                "/Movies/",
                "/Alarms/",
                "/Audiobooks/",
                "/Music/",
                "/Notifications/",
                "/Podcasts/",
                "/Ringtones/",
                "/Download/"
        };
        for (String s : mediaStorePath) {
            if (filePath.contains(s)) {
                isMediaStorePath = true;
                break;
            }
        }
        return isMediaStorePath;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) == PermissionChecker.PERMISSION_GRANTED;
    }

    private boolean pathRequiresPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        try {
            String appDirExternalFilePath = context.getExternalFilesDir(null).getCanonicalPath();
            String appDirExternalCachePath = context.getExternalCacheDir().getCanonicalPath();

            String appDirFilePath = context.getFilesDir().getCanonicalPath();
            String appDirCachePath = context.getCacheDir().getCanonicalPath();

            String fileCanonicalPath = new File(filePath).getCanonicalPath();
            if (fileCanonicalPath.startsWith(appDirExternalFilePath)
                    || fileCanonicalPath.startsWith(appDirExternalCachePath)
                    || fileCanonicalPath.startsWith(appDirFilePath)
                    || fileCanonicalPath.startsWith(appDirCachePath)) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return true;
        }
    }

    private boolean isFileAvailable() {
        if (filePath == null) {
            result(-4, "the file path cannot be null");
            return false;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            result(-2, "the " + filePath + " file does not exists");
            return false;
        }
        return true;
    }

    private void startActivity() {
        if (!isFileAvailable()) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileProvider.com.crazecoder.openfile", new File(filePath));
        } else {
            uri = Uri.fromFile(new File(filePath));
        }
        intent.setDataAndType(uri, typeString);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        List<ResolveInfo> resolveInfoList;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            resolveInfoList = activity.getPackageManager().queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        }else{
            resolveInfoList = activity.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        }
        for (ResolveInfo resolveInfo : resolveInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            activity.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        int type = 0;
        String message = "done";
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            type = -1;
            message = "No APP found to open this file。";
        } catch (Exception e) {
            type = -4;
            message = "File opened incorrectly。";
        }
        result(type, message);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean isExternalStoragePublicMedia(String mimeType) {
        return isExternalStoragePublicPath() && (isImage(mimeType) || isVideo(mimeType) || isAudio(mimeType));
    }

    private boolean isImage(String mimeType) {
        return mimeType.contains("image/");
    }

    private boolean isVideo(String mimeType) {
        return mimeType.contains("video/");
    }

    private boolean isAudio(String mimeType) {
        return mimeType.contains("audio/");
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean isExternalStoragePublicPath() {
        boolean isExternalStoragePublicPath = false;
        String[] mediaStorePath = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES).getPath()
                , Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_SCREENSHOTS).getPath()
        };
        for (String s : mediaStorePath) {
            if (filePath.contains(s)) {
                isExternalStoragePublicPath = true;
                break;
            }
        }
        return isExternalStoragePublicPath;
    }

    private String getFileMimeType(String filePath) {
        String[] fileStrs = filePath.split("\\.");
        String fileTypeStr = fileStrs[fileStrs.length - 1].toLowerCase();
        switch (fileTypeStr) {
            case "3gp":
                return "video/3gpp";
            case "torrent":
                return "application/x-bittorrent";
            case "kml":
                return "application/vnd.google-earth.kml+xml";
            case "gpx":
                return "application/gpx+xml";
            case "apk":
                return "application/vnd.android.package-archive";
            case "asf":
                return "video/x-ms-asf";
            case "avi":
                return "video/x-msvideo";
            case "bin":
            case "class":
            case "exe":
                return "application/octet-stream";
            case "bmp":
                return "image/bmp";
            case "c":
                return "text/plain";
            case "conf":
                return "text/plain";
            case "cpp":
                return "text/plain";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
            case "csv":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "gif":
                return "image/gif";
            case "gtar":
                return "application/x-gtar";
            case "gz":
                return "application/x-gzip";
            case "h":
                return "text/plain";
            case "htm":
                return "text/html";
            case "html":
                return "text/html";
            case "jar":
                return "application/java-archive";
            case "java":
                return "text/plain";
            case "jpeg":
                return "image/jpeg";
            case "jpg":
                return "image/jpeg";
            case "js":
                return "application/x-javascript";
            case "log":
                return "text/plain";
            case "m3u":
                return "audio/x-mpegurl";
            case "m4a":
                return "audio/mp4a-latm";
            case "m4b":
                return "audio/mp4a-latm";
            case "m4p":
                return "audio/mp4a-latm";
            case "m4u":
                return "video/vnd.mpegurl";
            case "m4v":
                return "video/x-m4v";
            case "mov":
                return "video/quicktime";
            case "mp2":
                return "audio/x-mpeg";
            case "mp3":
                return "audio/x-mpeg";
            case "mp4":
                return "video/mp4";
            case "mpc":
                return "application/vnd.mpohun.certificate";
            case "mpe":
                return "video/mpeg";
            case "mpeg":
                return "video/mpeg";
            case "mpg":
                return "video/mpeg";
            case "mpg4":
                return "video/mp4";
            case "mpga":
                return "audio/mpeg";
            case "msg":
                return "application/vnd.ms-outlook";
            case "ogg":
                return "audio/ogg";
            case "pdf":
                return "application/pdf";
            case "png":
                return "image/png";
            case "pps":
                return "application/vnd.ms-powerpoint";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "prop":
                return "text/plain";
            case "rc":
                return "text/plain";
            case "rmvb":
                return "audio/x-pn-realaudio";
            case "rtf":
                return "application/rtf";
            case "sh":
                return "text/plain";
            case "tar":
                return "application/x-tar";
            case "tgz":
                return "application/x-compressed";
            case "txt":
                return "text/plain";
            case "wav":
                return "audio/x-wav";
            case "wma":
                return "audio/x-ms-wma";
            case "wmv":
                return "audio/x-ms-wmv";
            case "wps":
                return "application/vnd.ms-works";
            case "xml":
                return "text/plain";
            case "z":
                return "application/x-compress";
            case "zip":
                return "application/x-zip-compressed";
            default:
                return "*/*";
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return false;
        for (String permission : permissions) {
            if (!hasPermission(permission)) {
                result(-3, "Permission denied: " + permission);
                return false;
            }
        }
        startActivity();
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent intent) {
        if (requestCode == RESULT_CODE) {
            startActivity();
        }
        return false;
    }

    private void result(int type, String message) {
        if (result != null && !isResultSubmitted) {
            Map<String, Object> map = MapUtil.createMap(type, message);
            result.success(JsonUtil.toJson(map));
            isResultSubmitted = true;
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        this.channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "open_file");
        this.channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel == null) {
            // Could be on too low of an SDK to have started listening originally.
            return;
        }

        channel.setMethodCallHandler(null);
        channel = null;
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
            // Do nothing
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        // Do nothing
    }
}
