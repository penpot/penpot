;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.state-helpers
  (:require
   #_[app.common.data :as d]
   #_[app.common.geom.proportions :as gpr]
   #_[app.common.geom.shapes :as gsh]
   #_[app.common.pages :as cp]
   #_[app.common.spec :as us]
   #_[app.common.uuid :as uuid]
   #_[app.main.data.workspace.changes :as dch]
   #_[app.main.data.workspace.undo :as dwu]
   #_[app.main.streams :as ms]
   #_[app.main.worker :as uw]
   #_[app.util.logging :as log]
   #_[beicon.core :as rx]
   #_[cljs.spec.alpha :as s]
   #_[potok.core :as ptk]))

(defn lookup-page-objects
  ([state]
   (lookup-page-objects state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :objects])))

(defn lookup-page-options
  ([state]
   (lookup-page-options state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :options])))

(defn lookup-component-objects
  ([state component-id]
   (get-in state [:workspace-data :components component-id :objects])))

