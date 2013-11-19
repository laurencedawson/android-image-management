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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import android.util.Log;
import android.util.LruCache;

import com.laurencedawson.image_management.util.ImageDownloadThread;
import com.laurencedawson.image_management.util.ImageThreadComparable;

public class ImageManager {

  public static final boolean DEBUG = false;
  public static final String TAG = "ImageManager";

  public static final int QUEUE_SIZE = 30;

  public static final int LONG_CONNECTION_TIMEOUT = 10000;
  public static final int LONG_REQUEST_TIMEOUT = 10000;

  public static final int MAX_WIDTH = 480;
  public static final int MAX_HEIGHT = 720;

  public static final int UI_PRIORITY = 1;
  public static final int BACKGROUND_PRIORITY = 0;

  public static final String GIF_MIME = "image/gif";
  public static final String CONTENT_TYPE = "content://";

  private Context mContext;
  private final LruCache<String, Bitmap> mBitmapCache;
  private final ExecutorService mThreadPool;

  // Inspired by Volley, only allow one thread to decode a bitmap
  private static final Object DECODE_LOCK = new Object();

  // Maintain 3 queues for image requests
  // The first queues requests to be processed immediately by the thread pool 
  private BlockingQueue<Runnable> mTaskQueue;
  // The second maintains a queue of active requests
  private ConcurrentLinkedQueue<Runnable> mActiveTasks;
  // The third maintains a queue of blocked requests. A request can be blocked
  // if a duplicate exists in the task or active queue
  private ConcurrentLinkedQueue<Runnable> mBlockedTasks;

  /**
   * Initialize a newly created ImageManager
   * @param context The application ofinal r activity context
   * @param cacheSize The size of the LRU cache
   * @param threads The number of threads for the pools to use
   */
  public ImageManager(final Context context, final int cacheSize, 
      final int threads ){

    // Instantiate the three queues. The task queue uses a custom comparator to 
    // change the ordering from FIFO (using the internal comparator) to respect
    // request priorities. If two requests have equal priorities, they are 
    // sorted according to creation date
    mTaskQueue =  new PriorityBlockingQueue<Runnable>(QUEUE_SIZE, 
        new ImageThreadComparable());
    mActiveTasks = new ConcurrentLinkedQueue<Runnable>();
    mBlockedTasks = new ConcurrentLinkedQueue<Runnable>();

    // The application context
    mContext = context;

    // Create a new threadpool using the taskQueue
    mThreadPool = new ThreadPoolExecutor(threads, threads, 
        Long.MAX_VALUE, TimeUnit.SECONDS, mTaskQueue){


      @Override
      protected void beforeExecute(final Thread thread, final Runnable run) {
        // Before executing a request, place the request on the active queue
        // This prevents new duplicate requests being placed in the active queue
        mActiveTasks.add(run);
        super.beforeExecute(thread, run);
      }

      @Override
      protected void afterExecute(final Runnable r, final Throwable t) {
        // After a request has finished executing, remove the request from
        // the active queue, this allows new duplicate requests to be submitted
        mActiveTasks.remove(r);


        // Perform a quick check to see if there are any remaining requests in
        // the blocked queue. Peek the head and check for duplicates in the 
        // active queues. If no duplicates exist, execute the request straight away.
        // Repeat this until a duplicate is found
        synchronized (mBlockedTasks) {
          while(mBlockedTasks.peek()!=null && !mActiveTasks.contains(mBlockedTasks.peek())){
            // !mTaskQueue.contains(mBlockedTasks.peek()) &&
            Runnable runnable = mBlockedTasks.poll();
            if(runnable!=null){
              mThreadPool.execute(runnable);
            }
          }
        }
        super.afterExecute(r, t);
      }
    };

    // Calculate the cache size
    final int actualCacheSize = 
        ((int) (Runtime.getRuntime().maxMemory() / 1024)) / cacheSize;

    // Create the LRU cache
    // http://developer.android.com/reference/android/util/LruCache.html

    // The items are no longer recycled as they leave the cache, turns out this wasn't the right
    // way to go about this and often resulted in recycled bitmaps being drawn
    // http://stackoverflow.com/questions/10743381/when-should-i-recycle-a-bitmap-using-lrucache
    mBitmapCache = new LruCache<String, Bitmap>(actualCacheSize){
      protected int sizeOf(final String key, final Bitmap value) {
        return value.getByteCount() / 1024;
      }
    };
  }

  /**
   * Remove all images from the LRU cache
   */
  public void removeAll() {
    synchronized (mBitmapCache) {
      mBitmapCache.evictAll();
    }
  }

  /**
   * Remove an Image from the LRU cache
   * @param url The URL of the element to remove
   */
  public void removeEntry(final String url){
    if(url==null){
      return;
    }

    synchronized (mBitmapCache) {
      mBitmapCache.remove(url);
    }
  }

  /**
   * Add a bitmap to the cache
   * @param url URL of the image
   * @param bitmap The bitmap image
   */
  private void addEntry(final String url, final Bitmap bitmap){

    if(url == null || bitmap == null){
      return;
    }

    synchronized (mBitmapCache) {
      if(mBitmapCache.get(url) == null){
        mBitmapCache.put(url, bitmap);
      }
    }
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

    // First try to grab the mime from the options
    Options options = new Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(getFullCacheFileName(mContext, url), options);
    if(options.outMimeType!=null && 
        options.outMimeType.equals(ImageManager.GIF_MIME)){
      return true;
    }

    return false;
  }


  /**
   * Get a specified image from the cache
   * @param url The URL of the image
   * @return A Bitmap of the image requested
   */
  public Bitmap get(String url) {
    if(url!=null){
      synchronized (mBitmapCache) {

        // Get the image from the cache
        final Bitmap bitmap = mBitmapCache.get(url);

        // Check if the bitmap is in the cache
        if (bitmap != null) {
          return bitmap;
        }
      }
    }

    return null;
  }

  /**
   * Request an image to be downloaded, cached and loaded into the LRU cache.
   * 
   * @param url The URL of the image to grab
   * @param request The ImageRequest complete with image options
   */
  public void requestImage(final ImageRequest request) {

    // If the request has no URL, abandon it
    if(request.mUrl==null){
      return;
    }

    // Create the image download runnable
    final ImageDownloadThread imageDownloadThread = new ImageDownloadThread(){

      public void run() {
        File file = null;

        // If the URL is not a local reseource, grab the file
        if(!getUrl().startsWith(CONTENT_TYPE)){

          // Grab a link to the file
          file = new File(getFullCacheFileName(mContext, getUrl()));
          
          // Log the image request
          if(DEBUG){
            Log.d(TAG, "Priority: "+getPriority()+"\tDisk: "+file.exists()+"\tCache: "+request.mCacheImage+"\t"+getUrl());
          }

          // If the file doesn't exist, grab it from the network
          if (!file.exists()){
            cacheImage( file, request);
          } 

          // Otherwise let the callback know the image is cached
          else if(request!=null){
            request.sendCachedCallback(getUrl(), true);
          }

          // Check if the file is a gif
          boolean isGif = isGif(getUrl());

          // If the file downloaded was a gif, tell all the callbacks
          if( isGif && request!=null ){
            request.sendGifCallback(getUrl());
          }
        }

        // Check if we should cache the image and the dimens
        boolean shouldCache = false;
        int maxWidth = MAX_WIDTH;
        int maxHeight = MAX_HEIGHT;
        if(request!=null){
          maxWidth = request.mMaxWidth;
          maxHeight = request.mMaxHeight;
          shouldCache = request.mCacheImage;
        }

        // If any of the callbacks request the image should be cached, cache it
        if(shouldCache && 
            (request!=null&&request.mContext!=null)||request==null){

          // First check the image isn't actually in the cache
          Bitmap bitmap = get(getUrl());

          // If the bitmap isn't in the cache, try to grab it
          // Or the bitmap was in the cache, but is of no use
          if(bitmap == null){
            if(!getUrl().startsWith(CONTENT_TYPE)){
              bitmap = decodeBitmap(file.getPath(), maxWidth, maxHeight);
            }else{
              bitmap = decodeBitmap(getUrl(), maxWidth, maxHeight);
            }

            // If we grabbed the image ok, add to the cache
            if(bitmap!=null){
              addEntry(getUrl(), bitmap);
            }
          }

          // Send the cached callback
          if(request!=null){
            request.sendCallback(getUrl(), bitmap);
          }
        }
      }
    };

    // Set the url of the request
    imageDownloadThread.setUrl(request.mUrl);

    // Set the creation time of the request
    imageDownloadThread.setCreated(request.mCreated);

    // Assign a priority to the request
    if(request.mImageListener==null){
      // If there is no image listener, assign it background priority
      imageDownloadThread.setPriority(BACKGROUND_PRIORITY);
    }else{
      // If there is an image listener, assign it UI priority
      imageDownloadThread.setPriority(UI_PRIORITY);
    }

    // If the new request is not a duplicate of an entry of the active and 
    // task queues, add the request to the task queue
    if(!mTaskQueue.contains(imageDownloadThread) && 
        !mActiveTasks.contains(imageDownloadThread)){
      mThreadPool.execute(imageDownloadThread);
    }

    // If the request is a duplicate, add it to the blocked tasks queue
    else{
      mBlockedTasks.add(imageDownloadThread);
    }
  }

  /**
   * Grab and save an image directly to disk
   * @param file The Bitmap file
   * @param imageCallback The callback associated with the request
   */
  public static void cacheImage(final File file, ImageRequest imageCallback) {

    HttpURLConnection urlConnection = null;
    FileOutputStream fileOutputStream = null;
    InputStream inputStream = null;

    try{
      // Setup the connection
      urlConnection = (HttpURLConnection) new URL(imageCallback.mUrl).openConnection();
      urlConnection.setConnectTimeout(ImageManager.LONG_CONNECTION_TIMEOUT);
      urlConnection.setReadTimeout(ImageManager.LONG_REQUEST_TIMEOUT);
      urlConnection.setUseCaches(true);
      urlConnection.setInstanceFollowRedirects(true);

      // Set the progress to 0
      imageCallback.sendProgressUpdate(imageCallback.mUrl, 0);

      // Connect
      inputStream = urlConnection.getInputStream();

      // Do not proceed if the connection couldn't be established
      if(urlConnection.getResponseCode()==404){
        urlConnection.disconnect();
        return;
      }

      // Grab the length of the image
      int length = 0;
      try{
        String fileLength = urlConnection.getHeaderField("Content-Length");
        if(fileLength!=null){
          length = Integer.parseInt(fileLength);
        }
      }catch(NumberFormatException e){
        if(ImageManager.DEBUG){
          e.printStackTrace();
        }
      }

      // Write the input stream to disk
      fileOutputStream = new FileOutputStream(file, true);
      int byteRead = 0;
      int totalRead = 0;
      final byte[] buffer = new byte[8192];

      // Download the image
      while ((byteRead = inputStream.read(buffer)) != -1) {
        // Write the buffer to the file and update the total number of bytes
        // read so far (used for the callback)
        fileOutputStream.write(buffer, 0, byteRead);
        totalRead+=byteRead;

        // Update the callback with the current progress
        if(length>0){
          imageCallback.sendProgressUpdate(imageCallback.mUrl, 
              (int) (((float)totalRead/(float)length)*100) );
        }
      }

      // Tidy up after the download
      if (fileOutputStream != null){
        fileOutputStream.close();
      }

      // Sent the callback that the image has been downloaded
      imageCallback.sendCachedCallback(imageCallback.mUrl, true);

      if (inputStream != null){
        inputStream.close();
      }

      // Disconnect the connection
      urlConnection.disconnect();
    } catch (final MalformedURLException e) {
      if (ImageManager.DEBUG){
        e.printStackTrace();
      }

      // If the file exists and an error occurred, delete the file
      if (file != null){
        file.delete();
      }

    } catch (final IOException e) {
      if (ImageManager.DEBUG){
        e.printStackTrace();
      }

      // If the file exists and an error occurred, delete the file
      if (file != null){
        file.delete();
      }
    }
  }

  /**
   * Decode a Bitmap with a given max width and height
   * @param filename The image path
   * @param reqWidth The requested width of the resulting bitmap
   * @param reqHeight The requested height of the resulting bitmap
   * @return The Bitmap image
   */
  @SuppressLint("NewApi")
  private Bitmap decodeBitmap(final String filename, final int reqWidth, final int reqHeight) {

    // Serialize all decode on a global lock to reduce concurrent heap usage.
    synchronized (DECODE_LOCK) {

      // Load a scaled version of the bitmap
      Options opts = null;
      Bitmap bitmap = null;
      try{
        // Handle the bitmap as a file
        if(!filename.startsWith(CONTENT_TYPE)){
          File file = new File(filename);
          // Check if the file doesn't exist or has no content
          if(!file.exists() || file.exists() && file.length()==0 ){
            return null;
          }
          opts = getOptions(file,reqWidth,reqHeight);
          bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
        }

        // Handle the bitmap as a stream 
        else{
          Uri uri = Uri.parse(filename);

          // Grab the bitmap options
          opts = getOptions(uri, reqWidth, reqHeight);
          InputStream inputStream = mContext.getContentResolver().openInputStream(uri);
          bitmap = BitmapFactory.decodeStream(inputStream, null, opts);
          inputStream.close();

        }

        // return the decoded bitmap
        return bitmap;

      }catch(IOException e){
        if(DEBUG){
          e.printStackTrace();
        }
      }
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
  private Options getOptions(final File file, final int reqWidth, final int reqHeight){
    BitmapFactory.Options options = new Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), options);
    return getOptionsInner(options, reqWidth, reqHeight);
  }

  /**
   * Grab the Bitmap options for a given max width and height 
   * https://code.google.com/p/iosched
   * 
   * @param uri The uri to load the bitmap from
   * @param reqWidth The requested width of the resulting bitmap
   * @param reqHeight The requested height of the resulting bitmap
   * @return The options to be used for loading bitmaps
   */
  private Options getOptions(final Uri uri, final int reqWidth, final int reqHeight) 
      throws IOException{
    BitmapFactory.Options options = new Options();
    options.inJustDecodeBounds = true;
    InputStream inputStream = mContext.getContentResolver().openInputStream(uri);
    BitmapFactory.decodeStream(inputStream, null, options);
    inputStream.close();
    return getOptionsInner(options, reqWidth, reqHeight);
  }

  private Options getOptionsInner(final Options options, final int reqWidth, final int reqHeight){
    options.inJustDecodeBounds = false;
    options.inPurgeable = true;
    options.inInputShareable = true;
    options.inDither = true;

    final int height = options.outHeight;
    final int width = options.outWidth;
    int inSampleSize = 1;

    if (height > reqHeight || width > reqWidth) {

      // Calculate ratios of height and width to requested height and width
      final int heightRatio = Math.round((float) height / (float) reqHeight);
      final int widthRatio = Math.round((float) width / (float) reqWidth);

      // Choose the smallest ratio as inSampleSize value, this will guarantee a final image
      // with both dimensions larger than or equal to the requested height and width.
      inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;

      // This offers some additional logic in case the image has a strange
      // aspect ratio. For example, a panorama may have a much larger
      // width than height. In these cases the total pixels might still
      // end up being too large to fit comfortably in memory, so we should
      // be more aggressive with sample down the image (=larger inSampleSize).

      final float totalPixels = width * height;

      // Anything more than 2x the requested pixels we'll sample down further
      final float totalReqPixelsCap = reqWidth * reqHeight * 2;

      while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
        inSampleSize++;
      }

      while((height/inSampleSize)>2048 || (width/inSampleSize)>2048){
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
    if(url.startsWith("/storage")){
      return url;
    }
    return getCacheDir(context) + "/" + getCacheFileName(url);
  }

  /**
   * A hashing method that changes a string (like a URL) into a hash suitable 
   * for using as a disk filename.
   * @param url The URL of the image 
   * @return A filename
   */
  public static String getCacheFileName(final String url){
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
    final StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      final String hex = Integer.toHexString(0xFF & bytes[i]);
      if (hex.length() == 1) {
        stringBuilder.append('0');
      }
      stringBuilder.append(hex);
    }
    return stringBuilder.toString();
  }

  /**
   * Get the cache directory to store images in
   * @param context The calling {@link Context}
   * @return a {@link File} to save images in
   */
  private static File getCacheDir( final Context context ){
    if(context!=null){
      // Try to grab a reference to the external cache dir
      final File directory = context.getExternalCacheDir();

      // If the file is OK, return it
      if(directory!=null){
        return directory;
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

    // Grab the time now
    long time = new Date().getTime();

    // If we can access the external cache, empty that first
    if( context.getExternalCacheDir() != null ){
      String[] children = context.getExternalCacheDir().list();
      for (int i = children.length-1; i >= 0; i--){
        final File file = new File(context.getExternalCacheDir(), children[i]);
        final Date lastModified = new Date( file.lastModified() );
        final long difference = time - lastModified.getTime();
        final int days = (int) (difference / (24 * 60 * 60 * 1000));

        if(days>=maxDays){
          file.delete();
        }
      }
    }

    // If we can access the internal cache, empty that too
    if(context.getCacheDir() != null ){
      String[] children = context.getCacheDir().list();
      for (int i = children.length-1; i >= 0; i--){
        final File file = new File(context.getCacheDir(), children[i]);
        final Date lastModified = new Date( file.lastModified() );
        final long difference =  time - lastModified.getTime();
        final int days = (int) (difference / (24 * 60 * 60 * 1000));

        if(days>=maxDays){
          file.delete();
        }
      }
    }
  }
}