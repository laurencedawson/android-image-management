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


package com.laurencedawson.image_management.extras;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A replacement ImageView that skips the creation of Drawables, avoids 
 * additional onLayout passes and handles image resizing.
 * 
 * Great for ListViews etc where your Bitmaps are all roughly the same 
 * dimensions.
 * 
 * @author Laurence Dawson
 *
 */
public class SimpleImageView extends View {

  private Bitmap mBitmap;
  private final Matrix mMatrix;
  private final Paint mPaint;
  private boolean mInvalidate;

  public SimpleImageView(final Context context, final AttributeSet attrs) {
    super(context, attrs);
    mMatrix = new Matrix();
    mPaint = new Paint();
    mPaint.setFilterBitmap(true);
  }

  @Override
  protected void onLayout(final boolean changed, final int left, final int top, 
      final int right, final int bottom) {
    // Nah
  }

  public void setImageBitmap(final Bitmap bitmap) {
    this.mBitmap = bitmap;
    mInvalidate = true;
    postInvalidate();
  }

  public Bitmap getImageBitmap(){
    return this.mBitmap;
  }

  @Override
  protected void onDraw(final Canvas canvas) {
    if(mBitmap!=null){
      if(mInvalidate){
        float fw = (float)getActualWidth()/(float)mBitmap.getWidth();
        float fh = (float)getActualHeight()/(float)mBitmap.getHeight();
        float ratio = Math.min(fw, fh);

        mMatrix.reset();
        mMatrix.postScale(ratio, ratio);
        mMatrix.postTranslate((getWidth()-(ratio*mBitmap.getWidth()))/2,
            (getHeight()-(ratio*mBitmap.getHeight()))/2);
        mInvalidate = false;
      }

      canvas.drawBitmap(mBitmap, mMatrix, mPaint );
    }
  }

  public int getActualWidth(){
    return getWidth()-(getPaddingLeft()+getPaddingRight());
  }

  public int getActualHeight(){
    return getHeight()-(getPaddingTop()+getPaddingBottom());
  }
}