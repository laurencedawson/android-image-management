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

  private Bitmap bm;
  private Matrix m;
  private Paint p;
  private boolean mInvalidate;

  public SimpleImageView(Context context, AttributeSet attrs) {
    super(context, attrs);
    m = new Matrix();
    p = new Paint();
    p.setFilterBitmap(true);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    // Nah
  }

  public void setImageBitmap(Bitmap bm) {
    this.bm = bm;
    mInvalidate = true;
    postInvalidate();
  }

  public Bitmap getImageBitmap(){
    return this.bm;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if(bm!=null){
      if(mInvalidate){
        float fw = (float)getActualWidth()/(float)bm.getWidth();
        float fh = (float)getActualHeight()/(float)bm.getHeight();
        float ratio = Math.min(fw, fh);

        m.reset();
        m.postScale(ratio, ratio);
        m.postTranslate((getWidth()-(ratio*bm.getWidth()))/2, (getHeight()-(ratio*bm.getHeight()))/2);
        mInvalidate = false;
      }

      canvas.drawBitmap(bm, m, p );
    }
  }


  public int getActualWidth(){
    return getWidth()-(getPaddingLeft()+getPaddingRight());
  }

  public int getActualHeight(){
    return getHeight()-(getPaddingTop()+getPaddingBottom());
  }

}