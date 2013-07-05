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

package com.laurencedawson.image_management_sample;

import java.io.File;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;

import com.laurencedawson.image_management.ImageListener;
import com.laurencedawson.image_management.ImageManager;
import com.laurencedawson.image_management.ImageRequest;

public class MainActivity extends Activity {

  private ImageView mImageViewLeft, mImageViewRight;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    mImageViewLeft = (ImageView) findViewById(R.id.imageViewLeft);
    mImageViewRight = (ImageView) findViewById(R.id.imageViewRight);

    // Remove all cached files on start
    String[] files = getExternalCacheDir().list();
    for (int i = files.length-1; i >= 0; i--){
      File file = new File(getExternalCacheDir(), files[i]);
      file.delete();
    }

    // Grab the two images
    setupImage();
  }

  private void setupImage(){
    ImageManager mImageManager = ((CustomApplication)getApplicationContext()).getImageManager();

    mImageManager.requestImage(new ImageRequest("http://upload.wikimedia.org/wikipedia/commons/c/c5/Anthochaera_chrysoptera.jpg", MainActivity.this, true, new ImageListener(){
      @Override
      public void onReceive(String url, Bitmap bitmap) {
        mImageViewLeft.setImageBitmap(bitmap);
      }
    }));

    mImageManager.requestImage(new ImageRequest("http://upload.wikimedia.org/wikipedia/commons/f/f9/Water_Dolphin.jpg", MainActivity.this, true, new ImageListener(){
      @Override
      public void onReceive(String url, Bitmap bitmap) {
        mImageViewRight.setImageBitmap(bitmap);
      }
    }));
  }

}
