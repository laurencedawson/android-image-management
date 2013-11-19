package com.laurencedawson.image_management.util;

import java.util.Comparator;

/**
 * A simple comparator which favours ImageDownloadThreads with higher 
 * priorities (such as UI requests over background requests)
 * @author Laurence Dawson
 *
 */
public class ImageThreadComparable implements Comparator<Runnable>{

  @Override
  public int compare(final Runnable lhs, final Runnable rhs) {

    if(lhs instanceof ImageDownloadThread && rhs instanceof ImageDownloadThread){
      // Favour a higher priority
      if(((ImageDownloadThread)lhs).getPriority()>((ImageDownloadThread)rhs).getPriority()){
        return -1;
      } else if(((ImageDownloadThread)lhs).getPriority()<((ImageDownloadThread)rhs).getPriority()){
        return 1;
      } 

      // Favour a lower creation time
      else if(((ImageDownloadThread)lhs).getCreated()>((ImageDownloadThread)rhs).getCreated()){
        return 1;
      }else if(((ImageDownloadThread)lhs).getCreated()<((ImageDownloadThread)rhs).getCreated()){
        return -1;
      }
    }

    return 0;
  }  

}