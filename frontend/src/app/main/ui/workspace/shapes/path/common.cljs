;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path.common
  (:require
   [app.main.refs :as refs]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def primary-color "#1FDEA7")
(def secondary-color "#DB00FF")
(def black-color "#000000")
(def white-color "#FFFFFF")
(def gray-color "#B1B2B5")

(def current-edit-path-ref
  (let [selfn (fn [local]
                (let [id (:edition local)]
                  (get-in local [:edit-path id])))]
    (l/derived selfn refs/workspace-local)))

(defn make-edit-path-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id])]
     #(l/derived selfn refs/workspace-local))))

(defn make-content-modifiers-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id :content-modifiers])]
     #(l/derived selfn refs/workspace-local))))

