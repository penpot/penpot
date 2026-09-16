;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.render-wasm.api.video
  "Proof of concept: video frames painted as image fills.

   Skia has no video decoder, so the browser decodes and we only hand Skia a
   texture. Each attached video owns one WebGL texture; on every rendered frame
   the current frame is uploaded into that texture and WASM re-wraps it, so
   every shape whose image fill points at `image-id` paints it.

   The source is stored on the shape as `:video`, so it persists with the file
   and comes back on load. Drop a file into `frontend/resources/public/images/`,
   where it is served like any other static asset, and name it from the Video
   section of the design sidebar. `penpotAttachVideo(\"clip.mp4\")` does the same
   from the console for the selected shape, without persisting it.

   There is no backend support: nothing uploads video, so the source is a path
   the browser can reach on its own."
  (:require
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.mem.heap32 :as mem.h32]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.fills :as types.fills]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.render-wasm.api.webgl :as webgl]
   [clojure.string :as str]))

;; HTMLMediaElement.readyState: the element has data for the current position.
(def ^:private HAVE-CURRENT-DATA 2)

;; Videos live next to the other static assets, so a bare file name is enough.
(def ^:private assets-path "/images/")

;; image-id -> {:element :source :texture :texture-id :shape-id :image-id :last-time}
;; The texture keys arrive later, once the video has decoded its first frame.
(defonce ^:private videos (atom {}))

;; `api.cljs` owns the render loop and requires this namespace, so it installs
;; its requester here rather than being required back.
(defonce ^:private render-requester (atom nil))

(defn set-render-requester!
  [f]
  (reset! render-requester f))

(defn- request-render!
  []
  (when-let [request @render-requester]
    (request "video-frame")))

(defn- playable?
  "False in the render worker, which runs the same renderer with no DOM to
   build a video element in."
  []
  (exists? js/document))

(defn- send-frame!
  "Hands the texture to WASM, which re-wraps it as a Skia image and invalidates
   the tiles of every shape painting it. Same layout as `_store_image_from_texture`."
  [{:keys [shape-id image-id texture-id ^js element]}]
  (let [offset (mem/alloc->offset-32 48)
        heap32 (mem/get-heap-u32)]
    (mem.h32/write-uuid offset heap32 shape-id)
    (mem.h32/write-uuid (+ offset 4) heap32 image-id)
    (aset heap32 (+ offset 8) 0) ;; thumbnail flag
    (aset heap32 (+ offset 9) texture-id)
    (aset heap32 (+ offset 10) (.-videoWidth element))
    (aset heap32 (+ offset 11) (.-videoHeight element))
    (h/call wasm/internal-module "_update_image_from_texture")))

(defn- decoded-frame?
  [{:keys [^js element image-id texture-id]}]
  (and (some? texture-id)
       (>= (.-readyState element) HAVE-CURRENT-DATA)
       (pos? (.-videoWidth element))
       ;; rAF runs faster than most videos decode; skip the upload when
       ;; playback has not advanced since the last one.
       (not= (.-currentTime element) (get-in @videos [image-id :last-time]))))

(defn- upload-frame!
  [gl {:keys [^js element texture image-id] :as entry}]
  (when (decoded-frame? entry)
    (swap! videos assoc-in [image-id :last-time] (.-currentTime element))
    (webgl/upload-texture-source! gl texture element)
    (send-frame! entry)
    true))

(defn tick!
  "Uploads a frame for every attached video that advanced. Called from the
   renderer's rAF, before the frame is drawn."
  []
  (when (seq @videos)
    (when-let [gl (webgl/get-webgl-context)]
      (reduce (fn [uploaded entry]
                (or (upload-frame! gl entry) uploaded))
              false
              (vals @videos)))))

(defn active?
  "True while some attached video is playing, so the renderer keeps scheduling
   frames instead of settling."
  []
  (boolean (some (fn [{:keys [^js element]}] (not (.-paused element)))
                 (vals @videos))))

(defn detach!
  "Stops a video. The last frame stays on screen: the texture is deliberately
   kept alive because WASM holds a Skia image borrowing it."
  [image-id]
  (when-let [{:keys [^js element]} (get @videos image-id)]
    (.pause element)
    (.removeAttribute element "src")
    (.load element)
    (swap! videos dissoc image-id)))

(defn detach-all!
  []
  (run! detach! (keys @videos)))

(defn- register!
  "Gives the video a texture once it has decoded something to fill it with."
  [image-id ^js element]
  (if-let [gl (webgl/get-webgl-context)]
    (let [texture (webgl/create-webgl-texture-from-image gl element)]
      (swap! videos update image-id merge
             {:texture texture
              :texture-id (webgl/register-texture! texture)})
      (-> (.play element)
          ;; Nothing else would ask for a frame: `tick!` only runs inside a
          ;; render, and the loop only keeps itself alive once it has started.
          (.then (fn [_] (request-render!)))
          (.catch (fn [cause]
                    (log/error :hint "Could not play video" :cause cause)))))
    (log/error :hint "No WebGL context available for video")))

(defn resolve-url
  "Bare names resolve against the static asset folder; absolute paths and full
   URLs are used as given."
  [source]
  (if (or (str/starts-with? source "/")
          (str/includes? source "://"))
    source
    (str assets-path source)))

(defn- attach-element!
  [shape-id image-id source]
  (let [url (resolve-url source)
        element (js/document.createElement "video")]
    ;; Registered before it loads, so a second `sync-shapes!` does not start a
    ;; competing element for the same fill.
    (swap! videos assoc image-id
           {:element element
            :source source
            :shape-id shape-id
            :image-id image-id
            :last-time nil})
    ;; Same-origin assets need no CORS, but a remote video served without the
    ;; headers would taint the canvas and make `texImage2D` throw.
    (set! (.-crossOrigin element) "anonymous")
    (set! (.-muted element) true)
    (set! (.-loop element) true)
    (set! (.-playsInline element) true)
    (set! (.-onerror element)
          (fn [_]
            (log/error :hint "Could not load video" :url url)
            (detach! image-id)))
    (.addEventListener element "loadeddata"
                       (fn [] (register! image-id element))
                       #js {:once true})
    (set! (.-src element) url)
    nil))

(defn attach!
  "Plays `source` into the image fill `image-id` of `shape-id`.

   The video is muted and looped: browsers refuse to autoplay audible video, and
   there is no playback UI to unmute it with."
  [shape-id image-id source]
  (detach! image-id)
  (when (playable?)
    (attach-element! shape-id image-id source)))

(defn image-fill-id
  "Image painted by `shape`, from its fills or, for image shapes, its metadata."
  [shape]
  (or (first (types.fills/get-image-ids (types.fills/coerce (:fills shape))))
      (get-in shape [:metadata :id])))

(defn video-fill
  "The shape's fill image, when the uploaded media is video rather than a still."
  [shape]
  (->> (types.fills/coerce (:fills shape))
       (seq)
       (keep :fill-image)
       (filter #(cm/video-type? (:mtype %)))
       (first)))

(defn shape-source
  "Where `shape` plays its video from: an uploaded video asset if it has one,
   otherwise the `:video` attribute naming a file the browser can reach."
  [shape]
  (if-let [image (video-fill shape)]
    (cf/resolve-file-media image)
    (:video shape)))

(defn playing?
  [image-id]
  (boolean (when-let [^js element (get-in @videos [image-id :element])]
             (not (.-paused element)))))

(defn toggle-play!
  [image-id]
  (when-let [^js element (get-in @videos [image-id :element])]
    (if (.-paused element)
      (-> (.play element)
          (.then (fn [_] (request-render!)))
          (.catch (fn [cause]
                    (log/error :hint "Could not play video" :cause cause))))
      (.pause element))
    (not (.-paused element))))

(defn sync-shape!
  "Reconciles one shape with its `:video` attribute. Called whenever the
   attribute changes, so attaching is a plain shape edit and undo comes free.

   Returns nil: `set-wasm-attr!` treats what it gets back as pending image
   loads, and `detach!` would otherwise hand it the video registry."
  [shape]
  (when-let [image-id (image-fill-id shape)]
    (let [source (shape-source shape)]
      (cond
        (str/blank? source)
        (detach! image-id)

        (not= source (get-in @videos [image-id :source]))
        (attach! (:id shape) image-id source))))
  nil)

(defn sync-shapes!
  "Reconciles every attachment with `objects`: starts the videos the shapes ask
   for and stops the ones no shape wants any more. Run on page load, so a video
   survives a reload; an unchanged attachment keeps playing."
  [objects]
  (let [wanted (into {}
                     (keep (fn [[shape-id shape]]
                             (let [source (shape-source shape)]
                               (when-not (str/blank? source)
                                 (when-let [image-id (image-fill-id shape)]
                                   [image-id {:shape-id shape-id :source source}])))))
                     objects)]
    (doseq [[image-id {:keys [source]}] @videos]
      (when (not= source (get-in wanted [image-id :source]))
        (detach! image-id)))
    (doseq [[image-id {:keys [shape-id source]}] wanted]
      (when-not (contains? @videos image-id)
        (attach! shape-id image-id source)))))

(defn attach-to-selected!
  "Console entry point: attaches `source` to the selected image shape."
  [source]
  (let [state (deref st/state)
        shape (some->> (first (dsh/lookup-selected state))
                       (dsh/lookup-shape state))]
    (if-let [image-id (some-> shape image-fill-id)]
      (do (attach! (:id shape) image-id source) true)
      (do (log/error :hint "Select a shape with an image fill first"
                     :shape-id (:id shape)
                     :shape-type (:type shape))
          false))))

;; Reachable from the browser console while there is no UI for this.
(unchecked-set js/globalThis "penpotAttachVideo" attach-to-selected!)
(unchecked-set js/globalThis "penpotDetachVideos" detach-all!)
