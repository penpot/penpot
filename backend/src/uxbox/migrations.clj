;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.migrations
  (:require
   [mount.core :as mount :refer [defstate]]
   [uxbox.db :as db]
   [uxbox.config :as cfg]
   [uxbox.util.migrations :as mg]
   [uxbox.util.template :as tmpl]))

(def +migrations+
  {:name "uxbox-main"
   :steps
   [{:desc "Initial triggers and utils."
     :name "0001-main"
     :fn (mg/resource "migrations/0001.main.sql")}
    {:desc "Initial auth related tables"
     :name "0002-users"
     :fn (mg/resource "migrations/0002.users.sql")}
    {:desc "Initial projects tables"
     :name "0003-projects"
     :fn (mg/resource "migrations/0003.projects.sql")}
    {:desc "Initial tasks related tables"
     :name "0004-tasks"
     :fn (mg/resource "migrations/0004.tasks.sql")}
    {:desc "Initial libraries tables"
     :name "0005-libraries"
     :fn (mg/resource "migrations/0005.libraries.sql")}
    {:desc "Initial presence tables"
     :name "0006-presence"
     :fn (mg/resource "migrations/0006.presence.sql")}
    {:desc "Remove version"
     :name "0007.remove_version"
     :fn (mg/resource "migrations/0007.remove_version.sql")}]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate
  []
  (with-open [conn (db/open)]
    (mg/setup! conn)
    (mg/migrate! conn +migrations+)))

(defstate migrations
  :start (migrate))
