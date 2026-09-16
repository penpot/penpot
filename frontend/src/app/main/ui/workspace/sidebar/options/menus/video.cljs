;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.video
  "Proof of concept: plays a video into a shape's image fill.

   Nothing uploads video, so the source is a path the browser can already
   reach — a file under `frontend/resources/public/images/` or a full URL. Only
   the render-wasm renderer paints it; elsewhere the image fill still shows."
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

(mf/defc video-menu*
  [{:keys [ids image-id source is-asset]}]
  (let [;; `video/playing?` reads the element, which is outside app state, so
        ;; the button tracks it locally.
        playing* (mf/use-state #(video/playing? image-id))
        playing  (deref playing*)

        has-source (not (str/blank? source))

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
