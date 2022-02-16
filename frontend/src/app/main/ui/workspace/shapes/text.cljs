;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.attrs :as attrs]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks.mutable-observer :refer [use-mutable-observer]]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [app.util.text-editor :as ted]
   [app.util.text-svg-position :as utp]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

;; --- Text Wrapper for workspace

(mf/defc text-static-content
  [{:keys [shape]}]
  [:& fo/text-shape {:shape shape
                     :grow-type (:grow-type shape)}])

(defn- update-with-current-editor-state
  [{:keys [id] :as shape}]
  (let [editor-state-ref (mf/use-memo (mf/deps id) #(l/derived (l/key id) refs/workspace-editor-state))
        editor-state     (mf/deref editor-state-ref)

        content (:content shape)
        editor-content
        (when editor-state
          (-> editor-state
              (ted/get-editor-current-content)
              (ted/export-content)))]

    (cond-> shape
      (some? editor-content)
      (assoc :content (attrs/merge content editor-content)))))

(mf/defc text-resize-content
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id name grow-type] :as shape} (obj/get props "shape")

        ;; NOTE: this breaks the hooks rule of "no hooks inside
        ;; conditional code"; but we ensure that this component will
        ;; not reused if edition flag is changed with `:key` prop.
        ;; Without the `:key` prop combining the shape-id and the
        ;; edition flag, this will result in a react error. This is
        ;; done for performance reason; with this change only the
        ;; shape with edition flag is watching the editor state ref.
        shape (cond-> shape
                (true? (obj/get props "edition?"))
                (update-with-current-editor-state))

        mnt           (mf/use-ref true)
        paragraph-ref (mf/use-state nil)

        handle-resize-text
        (mf/use-callback
         (mf/deps id)
         (fn [entries]
           (when (seq entries)
             ;; RequestAnimationFrame so the "loop limit error" error is not thrown
             ;; https://stackoverflow.com/questions/49384120/resizeobserver-loop-limit-exceeded
             (timers/raf
              #(let [width  (obj/get-in entries [0 "contentRect" "width"])
                     height (obj/get-in entries [0 "contentRect" "height"])]
                 (when (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
                   (log/debug :msg "Resize detected" :shape-id id :width width :height height)
                   (st/emit! (dwt/resize-text id (mth/ceil width) (mth/ceil height)))))))))

        text-ref-cb
        (mf/use-callback
         (mf/deps handle-resize-text)
         (fn [node]
           (when node
             (timers/schedule
              #(when (mf/ref-val mnt)
                 (when-let [ps-node (dom/query node ".paragraph-set")]
                   (reset! paragraph-ref ps-node)))))))]

    (mf/use-effect
     (mf/deps @paragraph-ref handle-resize-text grow-type)
     (fn []
       (when-let [paragraph-node @paragraph-ref]
         (let [sub (->> (wapi/observe-resize paragraph-node)
                        (rx/observe-on :af)
                        (rx/subs handle-resize-text))]
           (log/debug :msg "Attach resize observer" :shape-id id :shape-name name)
           (fn []
             (rx/dispose! sub))))))

    (mf/use-effect
     (fn [] #(mf/set-ref-val! mnt false)))

    [:& fo/text-shape {:ref text-ref-cb
                       :shape shape
                       :grow-type (:grow-type shape)
                       :key (str "shape-" (:id shape))}]))


(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id dirty?] :as shape} (unchecked-get props "shape")
        edition-ref (mf/use-memo (mf/deps id) #(l/derived (fn [o] (= id (:edition o))) refs/workspace-local))
        edition?    (mf/deref edition-ref)
        shape-ref (mf/use-ref nil)

        local-position-data (mf/use-state nil)

        handle-change-foreign-object
        (fn [node]
          (when (some? node)
            (mf/set-ref-val! shape-ref node)
            (let [position-data (utp/calc-position-data node)
                  parent (dom/get-parent node)
                  parent-transform (dom/get-attribute parent "transform")
                  node-transform (dom/get-attribute node "transform")

                  parent-mtx (usvg/parse-transform parent-transform)
                  node-mtx (usvg/parse-transform node-transform)

                  ;; We need to see what transformation is applied in the DOM to reverse it
                  ;; before calculating the position data
                  mtx (-> (gmt/multiply parent-mtx node-mtx)
                          (gmt/inverse))

                  position-data
                  (->> position-data
                       (mapv #(merge % (-> (select-keys % [:x :y :width :height])
                                           (gsh/transform-rect mtx)))))]
              (reset! local-position-data position-data))))

        on-change-node (use-mutable-observer handle-change-foreign-object)]

    ;; When the text is "dirty?" we get recalculate the positions
    (mf/use-layout-effect
     (mf/deps id dirty?)
     (fn []
       (let [node (mf/ref-val shape-ref)
             position-data (utp/calc-position-data node)]
         (reset! local-position-data nil)
         (st/emit! (dch/update-shapes
                    [id]
                    (fn [shape]
                      (-> shape
                          (dissoc :dirty?)
                          (assoc :position-data position-data))))))))

    [:> shape-container {:shape shape}
     ;; We keep hidden the shape when we're editing so it keeps track of the size
     ;; and updates the selrect accordingly
     [:*
      [:g.text-shape {:ref on-change-node
                      :opacity (when (or edition? (some? (:position-data shape))) 0)
                      :pointer-events "none"}

       ;; The `:key` prop here is mandatory because the
       ;; text-resize-content breaks a hooks rule and we can't reuse
       ;; the component if the edition flag changes.
       [:& text-resize-content {:shape
                                (cond-> shape
                                  (:position-data shape)
                                  (dissoc :transform :transform-inverse))
                                :edition? edition?
                                :key (str id edition?)}]]

      (when (and (not edition?) (or (some? (:position-data shape)) (some? local-position-data)))
        (let [shape
              (cond-> shape
                (some? @local-position-data)
                (assoc :position-data @local-position-data))]
          [:g.text-svg {:pointer-events "none"}
           [:& svg/text-shape {:shape shape}]]))]]))
