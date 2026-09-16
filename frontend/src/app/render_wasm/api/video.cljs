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

   With `video-overlay-wasm/v1` on, an eligible shape has its frames stamped
   when the frame is composed rather than rastered into the tiles, so playback
   costs one textured quad instead of a tile re-raster. A shape carrying
   opacity, a blend mode, a blur, a shadow or a stroke cannot be stamped that
   way and does not play at all; `ineligible-reason` says which one is in the
   way so the sidebar can explain it.

   The source is either an uploaded video asset or the shape's `:video`
   attribute — a file under `frontend/resources/public/images/` or a full URL.
   `penpotAttachVideo(\"clip.mp4\")` does the same from the console for the
   selected shape, without persisting it."
  (:require
   [app.common.geom.rect :as grc]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.mem.heap32 :as mem.h32]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.fills :as types.fills]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.render-wasm.api.webgl :as webgl]
   [clojure.string :as str]))

;; HTMLMediaElement.readyState: the element has data for the current position.
(def ^:private HAVE-CURRENT-DATA 2)

;; Videos live next to the other static assets, so a bare file name is enough.
(def ^:private assets-path "/images/")

;; image-id -> {:element :source :texture :texture-id :shape-id :image-id
;;              :tex-width :tex-height :pending? :frame-handle :last-time}
;; The texture keys arrive later, once the video has decoded its first frame.
(defonce ^:private videos (atom {}))

(defn- update-entry!
  "Applies `f` to an attachment, and does nothing when it is already detached —
   frame callbacks and promises resolve after a detach."
  [image-id f & args]
  (swap! videos (fn [videos]
                  (if (contains? videos image-id)
                    (apply update videos image-id f args)
                    videos))))

(defn- frame-callbacks?
  "True when the element reports each presented frame on its own. Widely
   available, but the rAF poll stays as the fallback."
  [^js element]
  (fn? (.-requestVideoFrameCallback element)))

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

(def ineligible-reasons
  "Codes returned by `_get_video_eligibility`, matching `VideoIneligible` in
   `render-wasm/src/render/video.rs`. `0` means the video can be composited."
  {1 :no-video-fill
   2 :opacity
   3 :blend-mode
   4 :blur
   5 :shadow
   6 :stroke
   7 :masked})

(defn- call-with-uuid!
  [export id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module export
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn- register-image!
  "Tells the renderer this image is backed by a playing video, so its frames are
   stamped when the frame is composed instead of rastered into the tiles."
  [image-id]
  (when (wasm/live?)
    (call-with-uuid! "_register_video_image" image-id)))

(defn- unregister-image!
  [image-id]
  (when (wasm/live?)
    (call-with-uuid! "_unregister_video_image" image-id)))

(defn set-overlay-enabled!
  "Threads `video-overlay-wasm/v1` to the renderer. With it off, video frames
   keep going through the tiles."
  [enabled]
  (when (wasm/live?)
    (h/call wasm/internal-module "_set_video_overlay_enabled" (boolean enabled))))

(defn ineligible-reason
  "Why `shape-id` cannot have its video composited, or nil when it can. The
   renderer owns the rule, so the sidebar and the render path cannot disagree."
  [shape-id]
  (when (wasm/live?)
    (get ineligible-reasons (call-with-uuid! "_get_video_eligibility" shape-id))))

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
  [{:keys [^js element texture-id pending? last-time]}]
  (and (some? texture-id)
       (>= (.-readyState element) HAVE-CURRENT-DATA)
       (pos? (.-videoWidth element))
       (if (frame-callbacks? element)
         pending?
         ;; rAF runs faster than most videos decode; without the frame callback
         ;; skip the upload when playback has not advanced since the last one.
         (not= (.-currentTime element) last-time))))

(defn visible-in-viewport?
  "True when `selrect` meets `vbox`. An unknown viewport or unknown bounds —
   the viewer, a page still loading — count as visible, so the video keeps
   painting rather than going blank."
  [vbox selrect]
  (or (nil? vbox)
      (nil? selrect)
      (grc/overlaps-rects? vbox selrect)))

(defn- on-screen?
  "A video outside the viewport keeps playing — so it stays in step with the
   others and resumes at the right moment — but stops paying for a texture
   upload."
  [shape-id]
  (let [state (deref st/state)]
    (visible-in-viewport? (get-in state [:workspace-local :vbox])
                          (:selrect (dsh/lookup-shape state shape-id)))))

(defn- upload-frame!
  [gl {:keys [^js element texture image-id shape-id tex-width tex-height] :as entry}]
  (when (decoded-frame? entry)
    (if-not (on-screen? shape-id)
      (update-entry! image-id assoc :pending? false :last-time (.-currentTime element))
      (let [width  (.-videoWidth element)
            height (.-videoHeight element)]
        ;; An adaptive stream can switch resolution mid-playback, and the
        ;; texture storage has to be redefined when it does.
        (if (and (= width tex-width) (= height tex-height))
          (webgl/update-texture-source! gl texture element)
          (do
            (webgl/upload-texture-source! gl texture element)
            (update-entry! image-id assoc :tex-width width :tex-height height)))
        (update-entry! image-id assoc :pending? false :last-time (.-currentTime element))
        (send-frame! entry)
        true))))

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
  "True while some attached video needs the renderer to keep scheduling frames
   instead of settling. A video reporting its own frames asks for a render when
   it has one, so only the polling fallback keeps the loop awake."
  []
  (boolean (some (fn [{:keys [^js element]}]
                   (and (not (.-paused element))
                        (not (frame-callbacks? element))))
                 (vals @videos))))

(defn- request-frame-callback!
  "Asks the element to report its next presented frame, and re-arms itself from
   the callback so the chain lasts as long as the attachment does."
  [image-id ^js element]
  (when (and (frame-callbacks? element)
             (contains? @videos image-id)
             (nil? (get-in @videos [image-id :frame-handle])))
    (let [handle (.requestVideoFrameCallback
                  element
                  (fn [_now _metadata]
                    (when (contains? @videos image-id)
                      (update-entry! image-id assoc :pending? true :frame-handle nil)
                      (request-render!)
                      (request-frame-callback! image-id element))))]
      (update-entry! image-id assoc :frame-handle handle))))

(defn- cancel-frame-callback!
  [^js element handle]
  (when (and (some? handle) (fn? (.-cancelVideoFrameCallback element)))
    (.cancelVideoFrameCallback element handle)))

(defn detach!
  "Stops a video. The last frame stays on screen: the texture is deliberately
   kept alive because WASM holds a Skia image borrowing it."
  [image-id]
  (when-let [{:keys [^js element frame-handle]} (get @videos image-id)]
    ;; Dropped from the registry first: the frame callback re-arms itself and
    ;; checks the registry to know when to stop.
    (swap! videos dissoc image-id)
    (unregister-image! image-id)
    (cancel-frame-callback! element frame-handle)
    (.pause element)
    (.removeAttribute element "src")
    (.load element)))

(defn detach-all!
  []
  (run! detach! (keys @videos)))

(defn- register!
  "Gives the video a texture once it has decoded something to fill it with."
  [image-id ^js element]
  (if-let [gl (webgl/get-webgl-context)]
    (let [texture (webgl/create-webgl-texture-from-image gl element)]
      (register-image! image-id)
      (update-entry! image-id merge
                     {:texture texture
                      :texture-id (webgl/register-texture! texture)
                      ;; `create-webgl-texture-from-image` allocated the storage
                      ;; at this size; later frames only overwrite its pixels.
                      :tex-width (.-videoWidth element)
                      :tex-height (.-videoHeight element)
                      :pending? true})
      (request-frame-callback! image-id element)
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
            :tex-width nil
            :tex-height nil
            :pending? false
            :frame-handle nil
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
      (do
        (request-frame-callback! image-id element)
        (-> (.play element)
            (.then (fn [_] (request-render!)))
            (.catch (fn [cause]
                      (log/error :hint "Could not play video" :cause cause)))))
      (.pause element))
    (not (.-paused element))))

(defn- overlay-enabled?
  []
  (contains? (:features (deref st/state)) "video-overlay-wasm/v1"))

(defn refuses-to-play
  "Why `shape` will not play its video, or nil when it will. Only the composited
   path refuses: with the flag off, frames still go through the tiles and any
   shape can carry them."
  [shape]
  (when (overlay-enabled?)
    (ineligible-reason (:id shape))))

(defn sync-shape!
  "Reconciles one shape with its `:video` attribute. Called whenever the
   attribute changes, so attaching is a plain shape edit and undo comes free.
   Also called when the shape's effects change, so adding a drop shadow to a
   playing video stops it there and then.

   Returns nil: `set-wasm-attr!` treats what it gets back as pending image
   loads, and `detach!` would otherwise hand it the video registry."
  [shape]
  (when-let [image-id (image-fill-id shape)]
    (let [source (shape-source shape)]
      (cond
        (str/blank? source)
        (detach! image-id)

        ;; A flat stamp cannot reproduce opacity, blending, blur, a shadow or a
        ;; stroke, so a shape carrying one shows its poster frame instead.
        (some? (refuses-to-play shape))
        (detach! image-id)

        (not= source (get-in @videos [image-id :source]))
        (attach! (:id shape) image-id source))))
  nil)

(defn sync-shapes!
  "Reconciles every attachment with `objects`: starts the videos the shapes ask
   for and stops the ones no shape wants any more. Run on page load, so a video
   survives a reload; an unchanged attachment keeps playing."
  [objects]
  ;; Cheap, and this is where the renderer learns the flag: it runs on every
  ;; page load, and the flag can change between them.
  (set-overlay-enabled! (overlay-enabled?))
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
        (when-not (and (overlay-enabled?) (some? (ineligible-reason shape-id)))
          (attach! shape-id image-id source))))))

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
