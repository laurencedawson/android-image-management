package com.laurencedawson.image_management.util;

/**
 * A simple Runnable object that can be given a priority, to be used with
 * ImageThreadComparator and PriorityBlockingQueue
 * @author Laurence Dawson
 *
 */

public class ImageDownloadThread implements Runnable{      
  private int priority;       
  private String url;
  private long created;


  @Override
  public void run() {
    // To be overridden
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(final int priority) {
    this.priority = priority;
  }

  public String getUrl(){
    return url;
  }

  public void setUrl(String url){
    this.url = url;
  }

  public long getCreated(){
    return created;
  }

  public void setCreated(long created){
    this.created = created;
  }

  @Override
  public boolean equals(final Object o) {

    if(getUrl()==null){
      return false;
    }

    if(o==null){
      return false;
    }

    if(o instanceof ImageDownloadThread &&
        ((ImageDownloadThread)o).getUrl().equals(getUrl())){
      return true;
    }
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    return getUrl().hashCode();
  }
}