;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.events
  (:require
   [app.common.data.macros :as dm]
   [app.main.store :as st]
   [app.plugins.file :as file]
   [app.plugins.page :as page]
   [goog.functions :as gf]))

(defmulti handle-state-change (fn [type _] type))

(defmethod handle-state-change "finish"
  [_ _ old-val new-val]
  (let [old-file-id (:current-file-id old-val)
        new-file-id (:current-file-id new-val)]
    (if (and (some? old-file-id) (nil? new-file-id))
      (str old-file-id)
      ::not-changed)))

(defmethod handle-state-change "filechange"
  [_ plugin-id old-val new-val]
  (let [old-file (:workspace-file old-val)
        new-file (:workspace-file new-val)
        old-data (:workspace-data old-val)
        new-data (:workspace-data new-val)]
    (if (and (identical? old-file new-file)
             (identical? old-data new-data))
      ::not-changed
      (file/file-proxy plugin-id (:id new-file)))))

(defmethod handle-state-change "pagechange"
  [_ plugin-id old-val new-val]
  (let [file-id     (:current-file-id new-val)
        old-page-id (:current-page-id old-val)
        new-page-id (:current-page-id new-val)
        old-page    (dm/get-in old-val [:workspace-data :pages-index old-page-id])
        new-page    (dm/get-in new-val [:workspace-data :pages-index new-page-id])]
    (if (identical? old-page new-page)
      ::not-changed
      (page/page-proxy plugin-id file-id new-page-id))))

(defmethod handle-state-change "selectionchange"
  [_ _ old-val new-val]
  (let [old-selection (get-in old-val [:workspace-local :selected])
        new-selection (get-in new-val [:workspace-local :selected])]
    (if (identical? old-selection new-selection)
      ::not-changed
      (apply array (map str new-selection)))))

(defmethod handle-state-change "themechange"
  [_ _ old-val new-val]
  (let [old-theme (get-in old-val [:profile :theme])
        new-theme (get-in new-val [:profile :theme])]
    (if (identical? old-theme new-theme)
      ::not-changed
      (if (= new-theme "default")
        "dark"
        new-theme))))

(defmethod handle-state-change :default
  [_ _ _ _]
  ::not-changed)

(defn add-listener
  [type plugin-id callback]
  (let [key (js/Symbol)
        callback (gf/debounce callback 10)]
    (add-watch
     st/state key
     (fn [_ _ old-val new-val]
       (let [result (handle-state-change type plugin-id old-val new-val)]
         (when (not= ::not-changed result)
           (callback result)))))

    ;; return the generated key
    key))

(defn remove-listener
  [key]
  (remove-watch st/state key))
