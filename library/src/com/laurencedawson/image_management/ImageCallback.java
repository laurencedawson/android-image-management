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

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;

public class ImageCallback{

  public Context mContext;
  public ImageListener mImageListener;
  public boolean mCacheImage;
  public int mLoadDelay;
  public int mMaxWidth;
  public int mMaxHeight;

  /**
   * Initialize a newly created ImageCallback object
   * @param context The calling {@link Context}
   * @param cache Should the image requested be cached in memory
   * @param delay Should the request delay when loading
   * @param maxWidth The maximum width the image should have
   * @param maxHeight The maximum height the image should have
   * @param listener An ImageListener to enable {@link ImageManager} callbacks
   */
  public ImageCallback( Context context, boolean cache, int delay, int maxWidth,
      int maxHeight, ImageListener listener){
    this.mContext = context;
    this.mCacheImage = cache;
    this.mLoadDelay = delay;
    this.mMaxWidth = maxWidth;
    this.mMaxHeight = maxHeight;
    this.mImageListener = listener;
  }

  /**
   * Initialize a newly created ImageCallback object with fewer params
   * @param context The calling {@link Context}
   * @param cache Should the image requested be cached in memory
   * @param listener An ImageListener to enable {@link ImageManager} callbacks
   */
  public ImageCallback( Context context, boolean cache, ImageListener listener){
    this.mContext = context;
    this.mCacheImage = cache;
    this.mLoadDelay = ImageManager.NO_DELAY;
    this.mMaxWidth = ImageManager.MAX_WIDTH;
    this.mMaxHeight = ImageManager.MAX_HEIGHT;
    this.mImageListener = listener;
  }

  /**
   * Send a callback to the ImageListener that the image loaded was a GIF
   * @param source The URL of the callback
   */
  public void sendGifCallback( final String source ){
    if(mContext!=null && mImageListener!=null){
      ((Activity) mContext).runOnUiThread(new Runnable() {
        public void run(){
          mImageListener.onGif( source );
        }
      });
    }
  }

  /**
   * Send a callback to the ImageListener that the image has been cached
   * @param source The URL of the callback
   * @param status The status of caching the image
   */
  public void sendCachedCallback( final String source, final boolean status ){
    if(mContext!=null && mImageListener!=null){
      ((Activity) mContext).runOnUiThread(new Runnable() {
        public void run(){
          mImageListener.onCached( source, status );
        }
      });
    }
  }

  /**
   * Send a callback to the ImageListener that the image was loaded
   * @param source The URL of the callback
   * @param bitmap The loaded {@link Bitmap} image
   */
  public void sendCallback( final String source, final Bitmap bitmap ){
    if(mContext!=null && mImageListener!=null){
      ((Activity) mContext).runOnUiThread(new Runnable() {
        public void run(){
          mImageListener.onReceive( source, bitmap );
        }
      });
    }
  }

  /**
   * Send a callback to the ImageListener that the progress of the image
   * downloading has changed
   * @param source The URL of the callback
   * @param progress The progress of the download
   */
  public void sendProgressUpdate( final String url, final int progress){
    if(mContext!=null && mImageListener!=null){
      ((Activity) mContext).runOnUiThread(new Runnable() {
        public void run(){
          mImageListener.onUpdate( url, progress );
        }
      });
    }
  }
}