;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
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
  [:& text/text-shape {:shape shape
                       :grow-type (:grow-type shape)}])

(defn- update-with-current-editor-state
  [{:keys [id] :as shape}]
  (let [editor-state-ref (mf/use-memo (mf/deps id) #(l/derived (l/key id) refs/workspace-editor-state))
        editor-state     (mf/deref editor-state-ref)]
    (cond-> shape
      (some? editor-state)
      (assoc :content (-> editor-state
                          (ted/get-editor-current-content)
                          (ted/export-content))))))

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

    [:& text/text-shape {:ref text-ref-cb :shape shape :grow-type (:grow-type shape)}]))

(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id] :as shape} (unchecked-get props "shape")
        edition-ref (mf/use-memo (mf/deps id) #(l/derived (fn [o] (= id (:edition o))) refs/workspace-local))
        edition?    (mf/deref edition-ref)]

    [:> shape-container {:shape shape}
     ;; We keep hidden the shape when we're editing so it keeps track of the size
     ;; and updates the selrect accordingly
     [:g.text-shape {:opacity (when edition? 0)
                     :pointer-events "none"}

      ;; The `:key` prop here is mandatory because the
      ;; text-resize-content breaks a hooks rule and we can't reuse
      ;; the component if the edition flag changes.
      [:& text-resize-content {:shape shape
                               :edition? edition?
                               :key (str id edition?)}]]]))
