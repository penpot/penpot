;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.math :as mth]
   [app.common.thumbnails :as thc]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :refer [check-shape-props]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as fdm]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.thumbnails :as th]
   [app.util.timers :as tm]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defn frame-shape-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-shape-inner
      {::mf/wrap [#(mf/memo' % check-shape-props)]
       ::mf/wrap-props false
       ::mf/forward-ref true}
      [props ref]

      (let [shape      (unchecked-get props "shape")
            shape-id   (dm/get-prop shape :id)

            childs-ref (mf/with-memo [shape-id]
                         (refs/children-objects shape-id))
            childs     (mf/deref childs-ref)]

        [:& shape-container {:shape shape :ref ref}
         [:& frame-shape {:shape shape :childs childs}]
         (when *assert*
           [:& wsd/shape-debug {:shape shape}])]))))

(defn check-props
  [new-props old-props]
  (and (= (unchecked-get new-props "thumbnail?")
          (unchecked-get old-props "thumbnail?"))

       (identical?
        (unchecked-get new-props "objects")
        (unchecked-get old-props "objects"))

       ^boolean
       (check-shape-props new-props old-props)))

(defn nested-frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            objects    (dsh/lookup-page-objects @st/state)

            frame-id   (dm/get-prop shape :id)

            node-ref   (mf/use-ref nil)
            modifiers* (mf/with-memo [frame-id]
                         (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers  (mf/deref modifiers*)]

        (fdm/use-dynamic-modifiers objects (mf/ref-val node-ref) modifiers)
        [:& frame-shape {:shape shape :ref node-ref}]))))

(defn image-size
  [href]
  (p/create
   (fn [resolve _]
     (let [img (js/Image.)
           load-fn
           (fn []
             (let [width (.-naturalWidth img)
                   height (.-naturalHeight img)]
               (resolve {:width width :height height})))]
       (set! (.-onload img) load-fn)
       (set! (.-src img) href)))))

(defn check-thumbnail-size
  [image-node bounds file-id page-id frame-id]
  (let [href   (dom/get-attribute image-node "href")
        width  (dm/get-prop bounds :width)
        height (dm/get-prop bounds :height)
        [fixed-width fixed-height] (th/get-relative-size width height)]
    ;; Even if looks like we're doing a new request the browser caches the image
    ;; so really we don't. We need a different API to check the sizes
    (-> (image-size href)
        (p/then
         (fn [{:keys [width height]}]
           (when (or (not (mth/close? width fixed-width 5))
                     (not (mth/close? height fixed-height 5)))
             (st/emit! (dwt/update-thumbnail file-id page-id frame-id "frame" "check-thumbnail-size"))))))))

(defn root-frame-wrapper-factory
  [shape-wrapper]
  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape          (unchecked-get props "shape")
            thumbnail?     (unchecked-get props "thumbnail?")
            objects        (unchecked-get props "objects")

            file-id        (mf/use-ctx ctx/current-file-id)
            page-id        (mf/use-ctx ctx/current-page-id)
            frame-id       (dm/get-prop shape :id)

            container-ref  (mf/use-ref nil)
            content-ref    (mf/use-ref nil)

            bounds         (gsb/get-object-bounds objects shape {:ignore-margin? false})

            x              (dm/get-prop bounds :x)
            y              (dm/get-prop bounds :y)
            width          (dm/get-prop bounds :width)
            height         (dm/get-prop bounds :height)

            thumbnail-uri* (mf/with-memo [file-id page-id frame-id]
                             (let [object-id (thc/fmt-object-id file-id page-id frame-id "frame")]
                               (refs/workspace-thumbnail-by-id object-id)))
            thumbnail-uri  (mf/deref thumbnail-uri*)

            modifiers-ref  (mf/with-memo [frame-id]
                             (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers      (mf/deref modifiers-ref)

            hidden?        (true? (:hidden shape))
            content-visible? (or (not ^boolean thumbnail?) (not ^boolean thumbnail-uri))

            tries-ref      (mf/use-ref 0)
            imposter-ref   (mf/use-ref nil)
            imposter-loaded  (mf/use-state false)
            task-ref       (mf/use-ref nil)

            on-load        (mf/use-fn (fn []
                                        ;; We need to check if this is the culprit of the thumbnail regeneration.
                                        ;; (check-thumbnail-size (mf/ref-val imposter-ref) bounds file-id page-id frame-id)
                                        (mf/set-ref-val! tries-ref 0)
                                        (reset! imposter-loaded true)))
            on-error       (mf/use-fn
                            (fn []
                              (let [current-tries (mf/ref-val tries-ref)
                                    new-tries     (mf/set-ref-val! tries-ref (inc current-tries))
                                    delay-in-ms   (* (mth/pow 2 new-tries) 1000)
                                    retry-fn      (fn []
                                                    (let [imposter (mf/ref-val imposter-ref)]
                                                      (when-not (nil? imposter)
                                                        (dom/set-attribute! imposter "href" thumbnail-uri))))]
                                (when (< new-tries 8)
                                  (mf/set-ref-val! task-ref (tm/schedule delay-in-ms retry-fn))))))]

        ;; NOTE: we don't add deps because we want this to be executed
        ;; once on mount with only referenced the initial data
        (mf/with-effect []
          (when-not (some? thumbnail-uri)
            (tm/schedule-on-idle
             #(st/emit! (dwt/update-thumbnail file-id page-id frame-id "frame" "root-frame"))))

          #(when-let [task (mf/ref-val task-ref)]
             (d/close! task)))

        (mf/with-effect [thumbnail-uri]
          (when-let [task (mf/ref-val task-ref)]
            (d/close! task)))

        (fdm/use-dynamic-modifiers objects (mf/ref-val content-ref) modifiers)
        [:& shape-container {:shape shape}
         [:g.frame-container
          {:id (dm/str "frame-container-" frame-id)
           :key "frame-container"
           :opacity (when ^boolean hidden? 0)}

           ;; When there is no thumbnail, we generate a empty rect.
          (when (and (not ^boolean content-visible?) (not @imposter-loaded))
            [:g.frame-placeholder
             [:rect {:x x
                     :y y
                     :width width
                     :height height
                     :fill "url(#frame-placeholder-gradient)"}]])

          [:g.frame-imposter
           [:image.thumbnail-bitmap
            {:x x
             :y y
             :ref imposter-ref
             :width width
             :height height
             :href thumbnail-uri
             :on-load on-load
             :on-error on-error
             :style {:display (when-not (and ^boolean thumbnail? ^boolean thumbnail-uri) "none")}}]

           ;; Render border around image when we are debugging
           ;; thumbnails.
           (when (dbg/enabled? :thumbnails)
             [:rect {:x (+ x 2)
                     :y (+ y 2)
                     :width (- width 4)
                     :height (- height 4)
                     :stroke "#f0f"
                     :stroke-width 2}])]

          ;; When thumbnail is disabled.
          (when ^boolean content-visible?
            [:g.frame-content
             {:id (dm/str "frame-content-" frame-id)
              :ref container-ref}
             [:& frame-shape {:shape shape :ref content-ref}]])]

         (when *assert*
           [:& wsd/shape-debug {:shape shape}])]))))

