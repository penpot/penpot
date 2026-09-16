;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.video
  "Plays a video into a shape's image fill.

   The source is an uploaded video asset, or a path the browser can already
   reach — a file under `frontend/resources/public/images/` or a full URL. Only
   the render-wasm renderer paints it; elsewhere the image fill still shows.

   A video is stamped when the frame is composed, which is a flat draw: it
   cannot carry opacity, a blend mode, a blur, a shadow or a stroke. A shape
   with one of those does not play, and this menu says which one is in the
   way."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.render-wasm.api.video :as video]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [clojure.string :as str]
   [rumext.v2 :as mf]))

(defn set-video
  "Stores the source on the shape. `app.render-wasm.shape` picks the change up
   and attaches or detaches the video, so undo and reload both behave."
  [ids source]
  (dwsh/update-shapes ids (fn [shape]
                            (if (str/blank? source)
                              (dissoc shape :video)
                              (assoc shape :video source)))))

(def ^:private reason-labels
  {:opacity "workspace.options.video.blocked.opacity"
   :blend-mode "workspace.options.video.blocked.blend-mode"
   :blur "workspace.options.video.blocked.blur"
   :shadow "workspace.options.video.blocked.shadow"
   :stroke "workspace.options.video.blocked.stroke"
   :masked "workspace.options.video.blocked.masked"})

(mf/defc video-menu*
  [{:keys [ids image-id source is-asset blocked-reason]}]
  (let [;; `video/playing?` reads the element, which is outside app state, so
        ;; the button tracks it locally.
        playing* (mf/use-state #(video/playing? image-id))
        playing  (deref playing*)

        blocked-label (get reason-labels blocked-reason)

        has-source (and (not (str/blank? source))
                        (nil? blocked-label))

        on-change
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value str/trim)]
             (st/emit! (set-video ids value)))))

        on-toggle-play
        (mf/use-fn
         (mf/deps image-id)
         (fn []
           (reset! playing* (video/toggle-play! image-id))))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (set-video ids nil))))]

    [:section {:class (stl/css :element-set)
               :aria-label (tr "workspace.options.video")}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable false
                      :title (tr "workspace.options.video")}
       (when has-source
         [:> icon-button* {:variant "ghost"
                           :aria-label (if playing
                                         (tr "workspace.options.video.pause")
                                         (tr "workspace.options.video.play"))
                           :on-click on-toggle-play
                           :selected playing
                           :tooltip-placement "top-left"
                           :icon i/play}])]]
     ;; Playback was refused: say which property is in the way, so a video that
     ;; stops after a shadow is added does not look broken.
     (when (some? blocked-label)
       [:div {:class (stl/css :blocked)}
        (tr blocked-label)])
     ;; An uploaded video has nothing to type: its source is the asset itself,
     ;; and removing it means deleting the shape.
     (when-not is-asset
       [:div {:class (stl/css :row)}
        [:> input* {:class (stl/css :source-input)
                    :placeholder (tr "workspace.options.video.placeholder")
                    :default-value (or source "")
                    :aria-label (tr "workspace.options.video.source")
                    :on-blur on-change}]
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.video.remove")
                          :on-click on-remove
                          :disabled (not has-source)
                          :tooltip-placement "top-left"
                          :icon i/remove}]])]))
