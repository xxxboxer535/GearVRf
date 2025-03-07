/* Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gearvrf;

import android.app.Activity;
import android.util.DisplayMetrics;

import org.gearvrf.debug.GVRFPSTracer;
import org.gearvrf.debug.GVRMethodCallTracer;
import org.gearvrf.debug.GVRStatsLine;
import org.gearvrf.io.GVRGearCursorController;
import org.gearvrf.utility.Log;
import org.gearvrf.utility.VrAppSettings;

/*
 * This is the most important part of gvrf.
 * Initialization can be told as 2 parts. A General part and the GL part.
 * The general part needs nothing special but the GL part needs a GL context.
 * Since something being done while the GL context creates a surface is time-efficient,
 * the general initialization is done in the constructor and the GL initialization is
 * done in onSurfaceCreated().
 * 
 * After the initialization, gvrf works with 2 types of threads.
 * Input threads, and a GL thread.
 * Input threads are about the sensor, joysticks, and keyboards. They send data to gvrf.
 * gvrf handles those data as a message. It saves the data, doesn't do something
 * immediately. That's because gvrf is built to do everything about the scene in the GL thread.
 * There might be some pros by doing some rendering related stuffs outside the GL thread,
 * but since I thought simplicity of the structure results in efficiency, I didn't do that.
 * 
 * Now it's about the GL thread. It lets the user handle the scene by calling the users GVRMain.onStep().
 * There are also GVRFrameListeners, GVRAnimationEngine, and Runnables but they aren't that special.
 */

/**
 * This is the core internal class.
 *
 * It implements {@link GVRContext}. It handles Android application callbacks
 * like cycles such as the standard Android {@link Activity#onResume()},
 * {@link Activity#onPause()}, and {@link Activity#onDestroy()}.
 *
 * <p>
 * Most importantly, {@link #onDrawFrame()} does the actual rendering, using the
 * current orientation from
 * {@link #onRotationSensor(long, float, float, float, float, float, float, float)
 * onRotationSensor()} to draw the scene graph properly.
 */
class OvrViewManager extends GVRViewManager {

    private static final String TAG = Log.tag(OvrViewManager.class);

    protected OvrLensInfo mLensInfo;
    protected int mCurrentEye;

    // Statistic debug info
    private GVRStatsLine mStatsLine;
    private GVRFPSTracer mFPSTracer;
    private GVRMethodCallTracer mTracerBeforeDrawEyes;
    private GVRMethodCallTracer mTracerAfterDrawEyes;
    private GVRMethodCallTracer mTracerDrawEyes;
    private GVRMethodCallTracer mTracerDrawEyes1;
    private GVRMethodCallTracer mTracerDrawEyes2;
    private GVRMethodCallTracer mTracerDrawFrame;
    private GVRMethodCallTracer mTracerDrawFrameGap;

    /**
     * Constructs OvrViewManager object with GVRMain which controls GL
     * activities
     *
     * @param application
     *            Current activity object
     * @param gvrMain
     *            {@link GVRMain} which describes
     */
    OvrViewManager(GVRApplication application, GVRMain gvrMain, OvrXMLParser xmlParser) {
        super(application, gvrMain);

        // Apply view manager preferences
        GVRPreference prefs = GVRPreference.get();
        DEBUG_STATS = prefs.getBooleanProperty(GVRPreference.KEY_DEBUG_STATS, false);
        DEBUG_STATS_PERIOD_MS = prefs.getIntegerProperty(GVRPreference.KEY_DEBUG_STATS_PERIOD_MS, 1000);
        try {
            GVRStatsLine.sFormat = GVRStatsLine.FORMAT
                    .valueOf(prefs.getProperty(GVRPreference.KEY_STATS_FORMAT, GVRStatsLine.FORMAT.DEFAULT.toString()));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        /*
         * Sets things with the numbers in the xml.
         */
        DisplayMetrics metrics = new DisplayMetrics();
        application.getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

        final float INCH_TO_METERS = 0.0254f;
        int screenWidthPixels = metrics.widthPixels;
        int screenHeightPixels = metrics.heightPixels;
        float screenWidthMeters = (float) screenWidthPixels / metrics.xdpi * INCH_TO_METERS;
        float screenHeightMeters = (float) screenHeightPixels / metrics.ydpi * INCH_TO_METERS;
        VrAppSettings vrAppSettings = application.getAppSettings();
        mLensInfo = new OvrLensInfo(screenWidthPixels, screenHeightPixels, screenWidthMeters, screenHeightMeters,
                vrAppSettings);

        // Debug statistics
        mStatsLine = new GVRStatsLine("gvrf-stats");

        mFPSTracer = new GVRFPSTracer("DrawFPS");
        mTracerDrawFrame = new GVRMethodCallTracer("drawFrame");
        mTracerDrawFrameGap = new GVRMethodCallTracer("drawFrameGap");
        mTracerBeforeDrawEyes = new GVRMethodCallTracer("beforeDrawEyes");
        mTracerDrawEyes = new GVRMethodCallTracer("drawEyes");
        mTracerDrawEyes1 = new GVRMethodCallTracer("drawEyes1");
        mTracerDrawEyes2 = new GVRMethodCallTracer("drawEyes2");
        mTracerAfterDrawEyes = new GVRMethodCallTracer("afterDrawEyes");

        mStatsLine.addColumn(mFPSTracer.getStatColumn());
        mStatsLine.addColumn(mTracerDrawFrame.getStatColumn());
        mStatsLine.addColumn(mTracerDrawFrameGap.getStatColumn());
        mStatsLine.addColumn(mTracerBeforeDrawEyes.getStatColumn());
        mStatsLine.addColumn(mTracerDrawEyes.getStatColumn());
        mStatsLine.addColumn(mTracerDrawEyes1.getStatColumn());
        mStatsLine.addColumn(mTracerDrawEyes2.getStatColumn());
        mStatsLine.addColumn(mTracerAfterDrawEyes.getStatColumn());

        mControllerReader = new OvrControllerReader(mApplication, mApplication.getActivityNative().getNative());
    }

    /**
     * Called when the surface is created or recreated. Avoided because this can
     * be called twice at the beginning.
     */
    void onSurfaceChanged(int width, int height) {
        Log.v(TAG, "onSurfaceChanged");

        final VrAppSettings.EyeBufferParams.DepthFormat depthFormat = mApplication.getAppSettings().getEyeBufferParams().getDepthFormat();
        mApplication.getConfigurationManager().configureRendering(VrAppSettings.EyeBufferParams.DepthFormat.DEPTH_24_STENCIL_8 == depthFormat);
    }

    @Override
    protected void beforeDrawEyes() {
        if (DEBUG_STATS) {
            mStatsLine.startLine();

            mTracerDrawFrame.enter();
            mTracerDrawFrameGap.leave();

            mTracerBeforeDrawEyes.enter();
        }

        super.beforeDrawEyes();

        if (DEBUG_STATS) {
            mTracerBeforeDrawEyes.leave();
        }
    }

    /**
     * Called from the native side
     * @param eye
     */
    void onDrawEye(int eye, int swapChainIndex, boolean use_multiview) {
        mCurrentEye = eye;
        if (!(mSensoredScene == null || !mMainScene.equals(mSensoredScene))) {
            GVRCameraRig mainCameraRig = mMainScene.getMainCameraRig();

            if (use_multiview) {

                if (DEBUG_STATS) {
                    mTracerDrawEyes1.enter(); // this eye is drawn first
                    mTracerDrawEyes2.enter();
                }
                 GVRRenderTarget renderTarget = mRenderBundle.getRenderTarget(EYE.MULTIVIEW, swapChainIndex);
                 GVRCamera camera = mMainScene.getMainCameraRig().getCenterCamera();
                 GVRCamera left_camera = mMainScene.getMainCameraRig().getLeftCamera();
                 renderTarget.cullFromCamera(mMainScene, camera,mRenderBundle.getShaderManager());

                captureCenterEye(renderTarget, true);
                capture3DScreenShot(renderTarget, true);

                renderTarget.render(mMainScene, left_camera, mRenderBundle.getShaderManager(),mRenderBundle.getPostEffectRenderTextureA(),
                        mRenderBundle.getPostEffectRenderTextureB());

                captureRightEye(renderTarget, true);
                captureLeftEye(renderTarget, true);

                captureFinish();

                if (DEBUG_STATS) {
                    mTracerDrawEyes1.leave();
                    mTracerDrawEyes2.leave();
                }


            } else {

                if (eye == 1) {
                    if (DEBUG_STATS) {
                        mTracerDrawEyes1.enter();
                    }

                     GVRCamera rightCamera = mainCameraRig.getRightCamera();
                     GVRRenderTarget renderTarget = mRenderBundle.getRenderTarget(EYE.RIGHT, swapChainIndex);
                     renderTarget.render(mMainScene, rightCamera, mRenderBundle.getShaderManager(), mRenderBundle.getPostEffectRenderTextureA(),
                             mRenderBundle.getPostEffectRenderTextureB());
                    captureRightEye(renderTarget, false);

                    captureFinish();
                    if (DEBUG_STATS) {
                        mTracerDrawEyes1.leave();
                        mTracerDrawEyes.leave();
                    }
                } else {
                    if (DEBUG_STATS) {
                        mTracerDrawEyes1.leave();
                        mTracerDrawEyes.leave();
                    }


                    GVRRenderTarget renderTarget = mRenderBundle.getRenderTarget(EYE.LEFT, swapChainIndex);
                    GVRCamera leftCamera = mainCameraRig.getLeftCamera();

                    capture3DScreenShot(renderTarget, false);

                    renderTarget.cullFromCamera(mMainScene, mainCameraRig.getCenterCamera(), mRenderBundle.getShaderManager());
                    captureCenterEye(renderTarget, false);
                    renderTarget.render(mMainScene, leftCamera, mRenderBundle.getShaderManager(), mRenderBundle.getPostEffectRenderTextureA(), mRenderBundle.getPostEffectRenderTextureB());

                    captureLeftEye(renderTarget, false);

                    if (DEBUG_STATS) {
                        mTracerDrawEyes2.leave();
                    }
                }
             }
        }
    }


    /** Called once per frame */
    protected void onDrawFrame() {
        drawEyes(mApplication.getActivityNative().getNative());
        afterDrawEyes();
    }

    @Override
    protected void afterDrawEyes() {
        if (DEBUG_STATS) {
            // Time afterDrawEyes from here
            mTracerAfterDrawEyes.enter();
        }

        super.afterDrawEyes();

        if (DEBUG_STATS) {
            mTracerAfterDrawEyes.leave();

            mTracerDrawFrame.leave();
            mTracerDrawFrameGap.enter();

            mFPSTracer.tick();
            mStatsLine.printLine(DEBUG_STATS_PERIOD_MS);

            mMainScene.addStatMessage(System.lineSeparator() + mStatsLine.getStats(GVRStatsLine.FORMAT.MULTILINE));
        }
    }

    @Override
    void onSurfaceCreated() {
        super.onSurfaceCreated();
        GVRGearCursorController gearController = mInputManager.getGearController();
        if (gearController != null)
            gearController.attachReader(mControllerReader);

    }
    void createSwapChain(){
        boolean isMultiview = mApplication.getAppSettings().isMultiviewSet();
        int width = mApplication.getAppSettings().getFramebufferPixelsWide();
        int height= mApplication.getAppSettings().getFramebufferPixelsHigh();
        for(int i=0;i < 3; i++){

            if(isMultiview){
                long renderTextureInfo = getRenderTextureInfo(mApplication.getActivityNative().getNative(), i, EYE.MULTIVIEW.ordinal());
                mRenderBundle.createRenderTarget(i, EYE.MULTIVIEW, new GVRRenderTexture(mApplication.getGVRContext(),  width , height,
                        GVRRenderBundle.getRenderTextureNative(renderTextureInfo)));
            }
            else {
                long renderTextureInfo = getRenderTextureInfo(mApplication.getActivityNative().getNative(), i, EYE.LEFT.ordinal());
                mRenderBundle.createRenderTarget(i, EYE.LEFT, new GVRRenderTexture(mApplication.getGVRContext(),  width , height,
                        GVRRenderBundle.getRenderTextureNative(renderTextureInfo)));
                renderTextureInfo = getRenderTextureInfo(mApplication.getActivityNative().getNative(), i, EYE.RIGHT.ordinal());
                mRenderBundle.createRenderTarget(i, EYE.RIGHT, new GVRRenderTexture(mApplication.getGVRContext(),  width , height,
                        GVRRenderBundle.getRenderTextureNative(renderTextureInfo)));
            }
        }

        mRenderBundle.createRenderTargetChain(isMultiview);
    }

    private native long getRenderTextureInfo(long ptr, int index, int eye);
    private native void drawEyes(long ptr);
    private native void recenterPose(long ptr);

    @Override
    public void recenterPose() {
        recenterPose(mApplication.getActivityNative().getNative());
    }
}
