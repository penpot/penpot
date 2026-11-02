;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.perms
  "Vocabulary for the admin permission model.

  Every admin method carries an implicit permission derived from its
  registered name (`admin:<method>`). `superuser` is not a permission
  here: it bypasses every check in `wrap-authentication`. Methods that
  declare it explicitly stay root-only and out of the grantable set."
  (:require
   [app.rpc :as-alias rpc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def admin-namespaces
  "Every namespace contributing methods to `/api/admin/methods`.
  Single source for the route resolver and the grantable vocabulary."
  '[app.rpc.admin.errors
    app.rpc.admin.file
    app.rpc.admin.job
    app.rpc.admin.misc
    app.rpc.admin.profile
    app.rpc.admin.project
    app.rpc.admin.team
    app.rpc.admin.tokens])

(defn admin-method?
  [mdata]
  (str/starts-with? (:ns mdata "") "app.rpc.admin."))

(defn method-perm
  [mdata]
  (str "admin:" (::sv/name mdata)))

(defn grantable-perms
  "Every implicit admin permission a superuser may grant on a token.
  Root-only methods (they declare `superuser`) are excluded: granting
  their implicit perm would be useless, the method never consults it."
  []
  (into #{}
        (comp (filter (fn [[_ mdata]] (admin-method? mdata)))
              (remove (fn [[_ mdata]]
                        (contains? (set (::rpc/perms mdata)) "superuser")))
              (map (fn [[_ mdata]] (method-perm mdata))))
        (apply sv/scan-ns admin-namespaces)))
