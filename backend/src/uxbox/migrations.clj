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

;; --- Migrations

(def +migrations+
  {:name "uxbox-main"
   :steps
   [{:desc "Initial triggers and utils."
     :name "0001-main"
     :fn (mg/resource "migrations/0001.main.up.sql")}
    {:desc "Initial auth related tables"
     :name "0002-auth"
     :fn (mg/resource "migrations/0002.auth.up.sql")}
    {:desc "Initial projects tables"
     :name "0003-projects"
     :fn (mg/resource "migrations/0003.projects.up.sql")}
    {:desc "Initial pages tables"
     :name "0004-pages"
     :fn (mg/resource "migrations/0004.pages.up.sql")}
    {:desc "Initial kvstore tables"
     :name "0005-kvstore"
     :fn (mg/resource "migrations/0005.kvstore.up.sql")}
    {:desc "Initial emails related tables"
     :name "0006-emails"
     :fn (mg/resource "migrations/0006.emails.up.sql")}
    {:desc "Initial images tables"
     :name "0007-images"
     :fn (mg/resource "migrations/0007.images.up.sql")}
    {:desc "Initial icons tables"
     :name "0008-icons"
     :fn (mg/resource "migrations/0008.icons.up.sql")}
    ]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate
  []
  (with-open [ctx (mg/context db/pool)]
    @(mg/migrate ctx +migrations+)))

(defstate migrations
  :start (migrate))
