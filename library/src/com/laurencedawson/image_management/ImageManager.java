/*
 * android-image-management for Android
 * Copyright (C) 2013 Laurence Dawson <contact@laurencedawson.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.laurencedawson.image_management;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.net.Uri;
import android.os.Build;
import android.support.v4.util.LruCache;

public class ImageManager {

  public static final boolean DEBUG = false;

  public static final int INITIAL_QUEUE_SIZE = 20;
  public static final int LONG_DELAY = 160;
  public static final int SHORT_DELAY = 80;
  public static final int LONG_CONNECTION_TIMEOUT = 10000;
  public static final int LONG_REQUEST_TIMEOUT = 10000;
  public static final int MAX_WIDTH = 480;
  public static final int MAX_HEIGHT = 720;
  public static final int NO_DELAY = 0;

  public static final String gifMime = "image/gif";

  private Context mContext;
  private final LruCache<String, Bitmap> mBitmapCache;
  private final ExecutorService mThreadPool[];

  // Inspired by Volley, only allow one thread to decode a bitmap
  private static final Object sDecodeLock = new Object();

  /**
   * Initialize a newly created ImageManager
   * @param context The application or activity context
   * @param cacheSize The size of the LRU cache
   * @param threads The number of threads for the pools to use
   */
  public ImageManager(Context context, int cacheSize, int threads ) {

    // The application context
    mContext = context;

    // Create a new individal pool for each thread
    mThreadPool =  new ExecutorService[threads];
    for(int i=0;i<threads;i++){

      // Based upon:
      // http://stackoverflow.com/questions/7792767/priority-threadpoolexecutor-in-java-android
      // Created a thread pool with a single thread backed by a priority queue,
      // this allows threads with higher priorities to jump up the queue. Great
      // for poor connections and background image caching
      mThreadPool[i] = 
          new ThreadPoolExecutor(1, 1, Long.MAX_VALUE, TimeUnit.SECONDS, 
              new PriorityBlockingQueue<Runnable>(INITIAL_QUEUE_SIZE,new ImageThreadComparator()));
    }

    // Calculate the cache size
    cacheSize = ((int) (Runtime.getRuntime().maxMemory() / 1024)) / cacheSize;

    // Create the LRU cache
    // http://developer.android.com/reference/android/util/LruCache.html
    mBitmapCache = new LruCache<String, Bitmap>(cacheSize){
      protected int sizeOf(String key, Bitmap value) {
        return value.getByteCount() / 1024;
      }

      @Override
      protected void entryRemoved(boolean evicted, String key, 
          Bitmap oldValue, Bitmap newValue) {
        if (oldValue != null){
          oldValue.recycle();
        }

        if (newValue != null){
          newValue.recycle();
        }

        super.entryRemoved(evicted, key, oldValue, newValue);
      }
    };
  }

  /**
   * Remove all images from the LRU cache
   */
  public void removeAll() {
    mBitmapCache.evictAll();
  }

  /**
   * Remove an Image from the LRU cache
   * @param url The URL of the element to remove
   */
  public void removeEntry(String url){
    if(url==null){
      return;
    }

    mBitmapCache.remove(url);
  }

  /**
   * Given an image URL, check if the cached image is a GIF
   * @param url The URL of the image
   * @return True if the image is a GIF
   */
  public boolean isGif(String url){
    if(url==null){
      return false;
    }

    Options o = new Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(getFullCacheFileName(mContext, url), o);
    return o.outMimeType!=null&&o.outMimeType.equals(ImageManager.gifMime);
  }

  /**
   * Get a specified image from the cache
   * @param url The URL of the image
   * @return A Bitmap of the image requested
   */
  public Bitmap get(String url) {
    if(url==null){
      return null;
    }

    // Get the image from the cache
    Bitmap bitmap = mBitmapCache.get(url);

    // Check if the bitmap is in the cache
    if (bitmap != null) {
      // If it hasn't been recycled return it
      if (!bitmap.isRecycled()){
        return bitmap;
      } else{
        // Otherwise remove it and return null
        removeEntry(url);
      }
    }

    return null;
  }

  /**
   * Request an image to be downloaded, cached and loaded into the LRU cache.
   * 
   * @param url The URL of the image to grab
   * @param callback The ImageCallback complete with image options
   */
  public void requestImage(final String url, final ImageCallback callback) {

    // Decide which pool to execute in
    int pool = Math.abs(url.hashCode()) % mThreadPool.length;

    // Create the image download runnable
    ImageDownloadThread imageDownloadThread = new ImageDownloadThread(){
      public void run() {

        // Sleep the request for the specified time
        if(callback!=null && callback.mLoadDelay>0){
          try {
            Thread.sleep(callback.mLoadDelay);
          } catch (InterruptedException e){
            if(ImageManager.DEBUG){
              e.printStackTrace();
            }
          }
        }

        File ff = null;

        // If the URL is not a local reseource, grab the file
        if(!url.startsWith("content://")){

          // Grab a link to the file
          ff = new File(getFullCacheFileName(mContext, url));

          // If the file doesn't exist, grab it from the network
          if (!ff.exists()){
            cacheImage( ff, url, callback);
          } 

          // Otherwise let the callback know the image is cached
          else if(callback!=null){
            callback.sendCachedCallback(url, true);
          }

          // Check if the file is a gif
          boolean isGif = isGif(url);

          // If the file downloaded was a gif, tell all the callbacks
          if( isGif && callback!=null ){
            callback.sendGifCallback(url);
          }
        }

        // Check if we should cache the image and the dimens
        boolean shouldCache = false;
        int maxWidth = MAX_WIDTH;
        int maxHeight = MAX_HEIGHT;
        if(callback!=null){
          maxWidth = callback.mMaxWidth;
          maxHeight = callback.mMaxHeight;
          shouldCache = callback.mCacheImage;
        }

        // If any of the callbacks request the image should be cached, cache it
        if(shouldCache && 
            ((callback!=null&&callback.mContext!=null)||callback==null)){

          // First check the image isn't actually in the cache
          Bitmap bitmap = mBitmapCache.get(url);

          // If the bitmap isn't in the cache, try to grab it
          // Or the bitmap was in the cache, but is of no use
          if(bitmap == null || (bitmap!=null && bitmap.isRecycled()) ){

            if(!url.startsWith("content://")){
              bitmap = decodeBitmap(ff, maxWidth, maxHeight);
            }else{
              Uri uri = Uri.parse(url);
              try{
                InputStream input = mContext.getContentResolver().openInputStream(uri);
                bitmap = BitmapFactory.decodeStream(input);
              }catch(FileNotFoundException e){
                if(DEBUG){
                  e.printStackTrace();
                }
              }
            }

            // If we grabbed the image ok, add to the cache
            if(bitmap!=null){
              mBitmapCache.put(url, bitmap);
            }
          }

          // Send the cached callback
          if(callback!=null){
            callback.sendCallback(url, bitmap);
          }

        }
      }
    };

    // Assign a priority to the request
    if(callback.mImageListener==null){
      // If there is no image listener, assign it background priority
      imageDownloadThread.setPriority(BACKGROUND_PRIORITY);
    }else{
      // If there is an image listener, assign it UI priority
      imageDownloadThread.setPriority(UI_PRIORITY);
    }

    mThreadPool[pool].execute(imageDownloadThread);
  }

  public static final int UI_PRIORITY = 1;
  public static final int BACKGROUND_PRIORITY = 0;

  /**
   * Grab and save an image directly to disk
   * @param file The Bitmap file
   * @param url The URL of the image
   * @param imageCallback The callback associated with the request
   */
  public static void cacheImage(final File file, final String url, 
      ImageCallback imageCallback) {

    HttpURLConnection urlConnection = null;
    FileOutputStream fileOutputStream = null;
    InputStream inputStream = null;
    boolean isGif = false;

    try{
      // Setup the connection
      urlConnection = (HttpURLConnection) new URL(url).openConnection();
      urlConnection.setConnectTimeout(ImageManager.LONG_CONNECTION_TIMEOUT);
      urlConnection.setReadTimeout(ImageManager.LONG_REQUEST_TIMEOUT);
      urlConnection.setUseCaches(true);
      urlConnection.setInstanceFollowRedirects(true);

      // Connect
      inputStream = urlConnection.getInputStream();

      // Do not proceed if the file wasn't downloaded
      if(urlConnection.getResponseCode()==404){
        urlConnection.disconnect();
        return;
      }

      // Check if the image is a GIF
      try{
        String contentType = urlConnection.getHeaderField("Content-Type");
        if(contentType!=null){
          isGif = contentType.equals(gifMime);
        }
      }catch(Exception e){
        if(ImageManager.DEBUG){
          e.printStackTrace();
        }
      }

      // Grab the length of the image
      int length = 0;
      try{
        String fileLength = urlConnection.getHeaderField("Content-Length");
        if(fileLength!=null){
          length = Integer.parseInt(fileLength);
        }
      }catch(Exception e){
        if(ImageManager.DEBUG){
          e.printStackTrace();
        }
      }

      // Write the input stream to disk
      fileOutputStream = new FileOutputStream(file, true);
      int byteRead = 0;
      int totalRead = 0;
      byte[] buffer = new byte[8192];
      int frameCount = 0;

      // Download the image
      while ((byteRead = inputStream.read(buffer)) != -1) {

        // If the image is a gif, count the start of frames
        if(isGif){
          for(int i=0;i<byteRead-3;i++){
            if( buffer[i] == 33 && buffer[i+1] == -7 && buffer[i+2] == 4 ){
              frameCount++;

              // Once we have at least one frame, stop the download
              if(frameCount>1){
                fileOutputStream.write(buffer, 0, i);
                fileOutputStream.close();

                if(imageCallback!=null){
                  imageCallback.sendProgressUpdate(url, 100);
                  imageCallback.sendCachedCallback(url, true);
                }

                urlConnection.disconnect();
                return;
              }
            }
          }
        }

        // Write the buffer to the file and update the total number of bytes
        // read so far (used for the callback)
        fileOutputStream.write(buffer, 0, byteRead);
        totalRead+=byteRead;

        // Update the callback with the current progress
        if(imageCallback!=null){
          if(length>0){
            imageCallback.sendProgressUpdate(url, 
                (int) (((float)totalRead/(float)length)*100) );
          }
        }
      }

      // Tidy up after the download
      if (fileOutputStream != null){
        fileOutputStream.close();
      }

      // Sent the callback that the image has been downloaded
      if(imageCallback!=null){
        imageCallback.sendCachedCallback(url, true);
      }

      if (inputStream != null){
        inputStream.close();
      }

      if (urlConnection!= null){
        urlConnection.disconnect();
      }

    }catch (Exception e) {
      if (ImageManager.DEBUG){
        e.printStackTrace();
      }

      // If the file exists and an error occured, delete the file
      if (file != null){
        file.delete();
      }

      // Tidy up after the download
      try {
        if (fileOutputStream != null){
          fileOutputStream.close();
        }

        if (inputStream != null){
          inputStream.close();
        }
      } catch (Exception ee) {
        if (ImageManager.DEBUG){
          ee.printStackTrace();
        }
      }

      if (urlConnection != null){
        urlConnection.disconnect();
      }
    }
  }

  /**
   * Decode a Bitmap with the default max width and height
   * @param file The Bitmap file
   * @return The Bitmap image
   */
  public static Bitmap decodeBitmap(File ff){
    return decodeBitmap(ff, ImageManager.MAX_WIDTH, ImageManager.MAX_WIDTH);
  }

  /**
   * Decode a Bitmap with a given max width and height
   * @param file The Bitmap file
   * @param reqWidth The requested width of the resulting bitmap
   * @param reqHeight The requested height of the resulting bitmap
   * @return The Bitmap image
   */
  @SuppressLint("NewApi")
  public static Bitmap decodeBitmap(File file, int reqWidth, int reqHeight) {

    // Serialize all decode on a global lock to reduce concurrent heap usage.
    synchronized (sDecodeLock) {

      // Check if the file doesn't exist or has no content
      if(!file.exists() || (file.exists() && file.length()==0) ){
        return null;
      }

      // Load a scaled version of the bitmap
      Options opts = null;
      opts = getOptions(file,reqWidth,reqHeight);

      // Set a few additional options for the bitmap opts
      opts.inPurgeable = true;
      opts.inInputShareable = true;
      opts.inDither = true;

      // Grab the bitmap
      Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);

      // If on JellyBean attempt to draw with mipmaps enabled
      if(bitmap!=null && 
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1){
        bitmap.setHasMipMap(true);
      }

      // return the decoded bitmap
      return bitmap;
    }
  }

  /**
   * Grab the Bitmap options for a given max width and height 
   * https://code.google.com/p/iosched
   * 
   * @param file The file to load options for
   * @param reqWidth The requested width of the resulting bitmap
   * @param reqHeight The requested height of the resulting bitmap
   * @return The options to be used for loading bitmaps
   */
  public static Options getOptions(File file, int reqWidth, int reqHeight) {
    BitmapFactory.Options options = new Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), options);
    options.inJustDecodeBounds = false;

    int inSampleSize = 1;
    int height = options.outHeight;
    int width = options.outWidth;

    if (height > reqHeight || width > reqWidth) {
      // Calculate ratios of height and width to requested height and width
      final int heightRatio = Math.round((float) height / (float) reqHeight);
      final int widthRatio = Math.round((float) width / (float) reqWidth);

      // Choose the smallest ratio as inSampleSize value, this will guarantee
      // a final image with both dimensions larger than or equal to the
      // requested height and width.
      inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;

      // This offers some additional logic in case the image has a strange
      // aspect ratio. For example, a panorama may have a much larger
      // width than height. In these cases the total pixels might still
      // end up being too large to fit comfortably in memory, so we should
      // be more aggressive with sample down the image (=larger
      // inSampleSize).
      final float totalPixels = width * height;

      // Anything more than 2x the requested pixels we'll sample down
      // further.
      final float totalReqPixelsCap = reqWidth * reqHeight * 2;

      while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
        inSampleSize++;
      }
    }

    options.inSampleSize = inSampleSize;
    return options;
  }

  /**
   * Grab the full cache name for an image
   * 
   * Based upon the following project
   * https://code.google.com/p/iosched
   * @param context The calling {@link Context}
   * @param url The URL of the image
   */
  public static String getFullCacheFileName(Context context, String url) {
    return getCacheDir(context) + "/" + getCacheFileName(url);
  }

  /**
   * A hashing method that changes a string (like a URL) into a hash suitable 
   * for using as a disk filename.
   * @param url The URL of the image 
   * @return A filename
   */
  public static String getCacheFileName(String url){
    String cacheKey;
    try {
      final MessageDigest mDigest = MessageDigest.getInstance("MD5");
      mDigest.update(url.getBytes());
      cacheKey = bytesToHexString(mDigest.digest());
    } catch (NoSuchAlgorithmException e) {
      cacheKey = String.valueOf(url.hashCode());
    }

    return cacheKey;
  }

  /**
   * Based upon the following project
   * https://code.google.com/p/iosched
   * @param bytes A byte array
   * @return A {@link String} to use for a filename
   */
  private static String bytesToHexString(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      String hex = Integer.toHexString(0xFF & bytes[i]);
      if (hex.length() == 1) {
        sb.append('0');
      }
      sb.append(hex);
    }
    return sb.toString();
  }

  /**
   * Get the cache directory to store images in
   * @param context The calling {@link Context}
   * @return a {@link File} to save images in
   */
  private static File getCacheDir( Context context ){
    if(context!=null){
      // Try to grab a reference to the external cache dir
      File dir = context.getExternalCacheDir();

      // If the file is OK, return it
      if(dir!=null){
        return dir;
      } 

      // If that fails, try to get a reference to the internal cache
      // This is a rare edge case but occasionally users don't have external
      // storage / their SDCard is removed / the folder is corrupt
      else{
        return context.getCacheDir();
      }
    }
    return null;
  }

  /**
   * Clear the local image cache of images over n days old
   * @param context The calling {@link Context}
   * @param int Max age of the images in days
   */
  public static void clearCache( Context context, final int maxDays ){
    try{
      // If we can access the external cache, empty that first
      if( context.getExternalCacheDir() != null ){
        String[] children = context.getExternalCacheDir().list();
        for (int i = children.length-1; i >= 0; i--){
          File file = new File(context.getExternalCacheDir(), children[i]);
          Date lastModified = new Date( file.lastModified() );
          long difference = new Date().getTime() - lastModified.getTime();
          int days = (int) (difference / (24 * 60 * 60 * 1000));

          if(days>=maxDays){
            file.delete();
          }
        }
      }

      // If we can access the internal cache, empty that too
      if(context.getCacheDir() != null ){
        String[] children = context.getCacheDir().list();
        for (int i = children.length-1; i >= 0; i--){
          File file = new File(context.getCacheDir(), children[i]);
          Date lastModified = new Date( file.lastModified() );
          long difference =  new Date().getTime() - lastModified.getTime();
          int days = (int) (difference / (24 * 60 * 60 * 1000));

          if(days>=maxDays){
            file.delete();
          }
        }
      }
    }catch(Exception e){
      if(ImageManager.DEBUG){
        e.printStackTrace();
      }
    }
  }

  /**
   * A simple comparator which favours ImageDownloadThreads with higher 
   * priorities (such as UI requests over background requests)
   * @author Laurence Dawson
   *
   */
  class ImageThreadComparator implements Comparator<Runnable>{

    @Override
    public int compare(Runnable lhs, Runnable rhs) {

      if(lhs instanceof ImageDownloadThread && rhs instanceof ImageDownloadThread){
        if(((ImageDownloadThread)lhs).getPriority()>((ImageDownloadThread)rhs).getPriority()){
          return -1;
        } else if(((ImageDownloadThread)lhs).getPriority()<((ImageDownloadThread)rhs).getPriority()){
          return 1;
        } 
      }

      return 0;
    }  

  }

  /**
   * A simple Runnable object that can be given a priority, to be used with
   * ImageThreadComparator and PriorityBlockingQueue
   * @author Laurence Dawson
   *
   */
  class ImageDownloadThread implements Runnable{      
    private int priority;       

    @Override
    public void run() {
      // To be overridden
    }

    public int getPriority() {
      return priority;
    }

    public void setPriority(int priority) {
      this.priority = priority;
    }       
  }
}