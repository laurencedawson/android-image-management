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

import android.graphics.Bitmap;

public class ImageListener{
  // The requested image was a gif
  public void onGif( final String url ){}

  // The requested image has been cached
  public void onCached ( final String url, final boolean status ){}

  // The request image has been cached and loaded into memory
  public void onReceive( final String url, final Bitmap bitmap ){}

  // The request image download progress
  public void onUpdate( final String url, final int progress ){}
}