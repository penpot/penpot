;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.events
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.plugins.file :as file]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.shape :as shape]
   [app.util.object :as obj]
   [goog.functions :as gf]))

(defmulti handle-state-change (fn [type _] type))

(defmethod handle-state-change "finish"
  [_ _ old-val new-val _]
  (let [old-file-id (:current-file-id old-val)
        new-file-id (:current-file-id new-val)]
    (if (and (some? old-file-id) (nil? new-file-id))
      (str old-file-id)
      ::not-changed)))

(defmethod handle-state-change "filechange"
  [_ plugin-id old-val new-val _]
  (let [old-file-id (:current-file-id old-val)
        new-file-id (:current-file-id new-val)]
    (if (identical? old-file-id new-file-id)
      ::not-changed
      (file/file-proxy plugin-id new-file-id))))

(defmethod handle-state-change "pagechange"
  [_ plugin-id old-val new-val _]
  (let [old-page-id (:current-page-id old-val)
        new-page-id (:current-page-id new-val)]
    (if (identical? old-page-id new-page-id)
      ::not-changed
      (page/page-proxy plugin-id (:current-file-id new-val) new-page-id))))

(defmethod handle-state-change "selectionchange"
  [_ _ old-val new-val _]
  (let [old-selection (get-in old-val [:workspace-local :selected])
        new-selection (get-in new-val [:workspace-local :selected])]
    (if (identical? old-selection new-selection)
      ::not-changed
      (apply array (map str new-selection)))))

(defmethod handle-state-change "themechange"
  [_ _ old-val new-val _]
  (let [old-theme (get-in old-val [:profile :theme])
        new-theme (get-in new-val [:profile :theme])]
    (if (identical? old-theme new-theme)
      ::not-changed
      (if (= new-theme "default")
        "dark"
        new-theme))))

(defmethod handle-state-change "shapechange"
  [_ plugin-id old-val new-val props]
  (if-let [shape-id (-> (obj/get props "shapeId") parser/parse-id)]
    (let [old-shape (dsh/lookup-shape old-val shape-id)
          new-shape (dsh/lookup-shape new-val shape-id)

          file-id (:current-file-id new-val)
          page-id (:current-page-id new-val)]
      (if (and (identical? old-shape new-shape) (some? plugin-id) (some? file-id) (some? page-id) (some? shape-id))
        ::not-changed
        (shape/shape-proxy plugin-id file-id page-id shape-id)))
    ::not-changed))

(defmethod handle-state-change "contentsave"
  [_ _ old-val new-val _]
  (let [old-status (dm/get-in old-val [:persistence :status])
        new-status (dm/get-in new-val [:persistence :status])]
    (if (and (= :saved new-status) (not= new-status old-status))
      ::void ;; Changed but void
      ::not-changed)))

(defmethod handle-state-change :default
  [_ _ _ _]
  ::not-changed)

(defn add-listener
  [type plugin-id callback props]
  (let [plugin-id (parser/parse-id plugin-id)
        key (js/Symbol)

        ;; We wrap the callback in an exception handler so the plugins
        ;; don't crash the application
        safe-callback
        (fn [value]
          (try
            (if (= ::void value)
              (callback)
              (callback value))
            (catch :default cause
              (.error js/console cause))))

        ;; We also debounce the callbacks so we don't get too many at the same time
        debounced-callback (gf/debounce safe-callback 10)]

    (add-watch
     st/state key
     (fn [_ _ old-val new-val]
       (let [result (handle-state-change type plugin-id old-val new-val props)]
         (when (not= ::not-changed result)
           (debounced-callback result)))))

    ;; return the generated key
    key))

(defn remove-listener
  [key]
  (remove-watch st/state key))
