/*
 * Copyright 2014 RobustNet Lab, University of Michigan. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.mobilyzer.measurements;

import java.io.InvalidClassException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.video.VideoPlayerService;
import com.mobilyzer.util.video.util.DemoUtil;

/**
 * @author laoyao
 * Measure the user-perceived Video QoE metrics by playing YouTube video in the background
 */
public class VideoQoETask extends MeasurementTask {
  // Type name for internal use
  public static final String TYPE = "video_qoe";
  // Human readable name for the task
  public static final String DESCRIPTOR = "VIDEOQOE";

  private boolean isSucceed = false;
  private int numFrameDropped;
  private double initialLoadingTime;
  private ArrayList<Double> rebufferTime = new ArrayList<Double>();
  private ArrayList<String> goodputTimestamp = new ArrayList<String>();
  private ArrayList<Double> goodputValue = new ArrayList<Double>();
  private ArrayList<String> bitrateTimestamp = new ArrayList<String>();
  private ArrayList<Integer> bitrateValue = new ArrayList<Integer>();
  
  private boolean isResultReceived;
  private long duration;
  /**
   * @author laoyao
   * Parameters for Video QoE measurement
   */
  public static class VideoQoEDesc extends MeasurementDesc {
    // The url to retrieve video manifest xml
    public String manifestURL;
    // The content id for YouTube video
    public String contentId;
    // The ABR algorithm for video playback
    public int ABRType;

    public VideoQoEDesc(String key, Date startTime, Date endTime, double intervalSec,
            long count, long priority, int contextIntervalSec, Map<String, String> params) {
        super(VideoQoETask.TYPE, key, startTime, endTime, intervalSec, count, priority,
                contextIntervalSec, params);
        initializeParams(params);
        if (this.manifestURL == null) {
            throw new InvalidParameterException("Video QoE task cannot be created"
                    + " due to null video manifest url string");
        }
    }

    @Override
    public String getType() {
        return VideoQoETask.TYPE;
    }

    @Override
    protected void initializeParams(Map<String, String> params) {
        if (params == null) {
            return;
        }
        String val = null;

        this.manifestURL = params.get("manifestURL");
        this.contentId = params.get("contentId");
        if ((val = params.get("ABRType")) != null && Integer.parseInt(val) > 0) {
          this.ABRType = Integer.parseInt(val);
        }

    }

    protected VideoQoEDesc(Parcel in) {
        super(in);
        manifestURL = in.readString();
        contentId = in.readString();
        ABRType = in.readInt();
    }

    public static final Parcelable.Creator<VideoQoEDesc> CREATOR =
            new Parcelable.Creator<VideoQoEDesc>() {
        public VideoQoEDesc createFromParcel(Parcel in) {
            return new VideoQoEDesc(in);
        }

        public VideoQoEDesc[] newArray(int size) {
            return new VideoQoEDesc[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(this.manifestURL);
        dest.writeString(this.contentId);
        dest.writeInt(this.ABRType);
    }
  }
  
  /**
   * Constructor for video QoE measuremen task
   * @param desc
   */
  public VideoQoETask(MeasurementDesc desc) {
    super(new VideoQoEDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
        desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
  }
  
  protected VideoQoETask(Parcel in) {
    super(in);
  }

  public static final Parcelable.Creator<VideoQoETask> CREATOR =
      new Parcelable.Creator<VideoQoETask>() {
    public VideoQoETask createFromParcel(Parcel in) {
      return new VideoQoETask(in);
    }

    public VideoQoETask[] newArray(int size) {
      return new VideoQoETask[size];
    }
  };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#clone()
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    VideoQoEDesc newDesc =
            new VideoQoEDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
                    desc.priority, desc.contextIntervalSec, desc.parameters);
    return new VideoQoETask(newDesc);
  }
  
  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#call()
   */
  @Override
  public MeasurementResult[] call() throws MeasurementError {
    Logger.d("Video QoE: measurement started");
    
    MeasurementResult[] mrArray = new MeasurementResult[1];
    VideoQoEDesc taskDesc = (VideoQoEDesc) this.measurementDesc;

    Intent videoIntent = new Intent(PhoneUtils.getGlobalContext(), VideoPlayerService.class);
    videoIntent.setData(Uri.parse(taskDesc.manifestURL));
    videoIntent.putExtra(DemoUtil.CONTENT_ID_EXTRA, taskDesc.contentId);
    videoIntent.putExtra(DemoUtil.CONTENT_TYPE_EXTRA, DemoUtil.TYPE_DASH_VOD);
    PhoneUtils.getGlobalContext().startService(videoIntent);


    IntentFilter filter = new IntentFilter();
    filter.addAction(UpdateIntent.VIDEO_MEASUREMENT_ACTION);
    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          Logger.d("Video QoE: result received");
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED)){
            isSucceed = intent.getBooleanExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED, false);
            Logger.d("Is succeed: " + isSucceed);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED)){
            numFrameDropped = intent.getIntExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED, 0);
            Logger.d("Num frame dropped: " + numFrameDropped);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME)){
            initialLoadingTime = intent.getDoubleExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME, 0.0);
            Logger.d("Initial Loading Time: " + initialLoadingTime);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME)) {
            double[] rebufferTimeArray = intent.getDoubleArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME);
            for (double rebuffer : rebufferTimeArray) {
              rebufferTime.add(rebuffer);
            }
            Logger.d("Rebuffer Time: " + rebufferTime);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP)) {
            String[] goodputTimestampArray = intent.getStringArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP);
            goodputTimestamp = new ArrayList<String>(Arrays.asList(goodputTimestampArray));
            Logger.d("Goodput Timestamp: " + goodputTimestamp);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE)) {
            double[] goodputValueArray = intent.getDoubleArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE);
            for (double goodput : goodputValueArray) {
              goodputValue.add(goodput);
            }
            Logger.d("Goodput Value: " + goodputValue);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP)) {
            String[] bitrateTimestampArray = intent.getStringArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP);
            bitrateTimestamp = new ArrayList<String>(Arrays.asList(bitrateTimestampArray));
            Logger.d("Bitrate Timestamp: " + bitrateTimestamp);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE)) {
            int[] bitrateValueArray = intent.getIntArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE);
            for (int bitrate : bitrateValueArray) {
              bitrateValue.add(bitrate);
            }
            Logger.d("Bitrate Value: " + bitrateValue);
          }
          isResultReceived = true;
        }
    };
    PhoneUtils.getGlobalContext().registerReceiver(broadcastReceiver, filter);



    for(int i=0;i<60*5;i++){
        if(isDone()){
            break;
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    Logger.e("Video QoE: result ready? " + this.isResultReceived);
    PhoneUtils.getGlobalContext().unregisterReceiver(broadcastReceiver);
    
    if(isDone()){
        Logger.i("Video QoE: Successfully measured QoE data");
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                VideoQoETask.TYPE, System.currentTimeMillis() * 1000,
                TaskProgress.COMPLETED, this.measurementDesc);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED, isSucceed);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED, this.numFrameDropped);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME, this.initialLoadingTime);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME, this.rebufferTime);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP, this.goodputTimestamp);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE, this.goodputValue);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP, this.bitrateTimestamp);
        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE, this.bitrateValue);

        Logger.i(MeasurementJsonConvertor.toJsonString(result));
        mrArray[0]=result;
    }else{
        Logger.i("Video QoE: Video measurement not finished");
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                VideoQoETask.TYPE, System.currentTimeMillis() * 1000,
                TaskProgress.FAILED, this.measurementDesc);
        result.addResult("error", "measurement timeout");
        Logger.i(MeasurementJsonConvertor.toJsonString(result));
        mrArray[0]=result;
    }

//    PhoneUtils.getGlobalContext().stopService(new Intent(PhoneUtils.getGlobalContext(), PLTExecutorService.class));
    return mrArray;
  }
  
  private boolean isDone() {
    return isResultReceived;
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
      return VideoQoEDesc.class;
  }
  
  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDescriptor()
   */
  @Override
  public String getDescriptor() {
    return VideoQoETask.DESCRIPTOR;
  }


  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getType()
   */
  @Override
  public String getType() {
    return VideoQoETask.TYPE;
  }


  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#stop()
   */
  @Override
  public boolean stop() {
    // There is nothing we need to do to stop the video measurement
    return false;
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDuration()
   */
  @Override
  public long getDuration() {
    return this.duration;
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#setDuration(long)
   */
  @Override
  public void setDuration(long newDuration) {
    if (newDuration < 0) {
      this.duration = 0;
    } else {
      this.duration = newDuration;
    }
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDataConsumed()
   */
  @Override
  public long getDataConsumed() {
    // TODO Auto-generated method stub
    return 0;
  }

}