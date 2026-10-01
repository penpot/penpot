;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.list
  "Shared building blocks for the admin list commands (`get-profiles`,
  `get-teams`, `get-projects`, `get-files`).

  Every list shares one contract: newest-first order
  (`created_at DESC, id DESC`) with keyset pagination, lookup by exact
  indexed fields only (`id`, plus exact `email` for profiles), and a
  tri-state `:deleted` filter (absent lists everything, `true` only
  deleted rows, `false` only live rows). Substring search has no usable
  index at PRO scale, so it is not offered."
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]))

(defn deleted-clause
  "WHERE fragment for the tri-state `:deleted` filter on `col`
  (a qualified `deleted_at` column). Returns nil when the filter is
  absent, so it can sit inside a `(keep identity …)` clause vector."
  [col deleted]
  (when (some? deleted)
    (if deleted
      {:where  (str col " IS NOT NULL")
       :params []}
      {:where  (str col " IS NULL")
       :params []})))

(defn since-clause
  "Keyset cursor fragment: rows older than `(since, since-id)` on the
  `(created-col, id-col)` pair. Returns nil when `since` is absent, so
  it can sit inside a `(keep identity …)` clause vector."
  [created-col id-col since since-id]
  (when since
    {:where  (str "(" created-col ", " id-col ") < (?::timestamptz, ?::uuid)")
     :params [since (or since-id uuid/zero)]}))

(defn with-fetch-limit
  "Clamp the requested `:limit` between the command default and max,
  and bump it by one so the caller can tell whether another page
  exists. Returns `[limit params]`."
  [params default-limit max-limit]
  (let [limit (min (or (:limit params) default-limit) max-limit)]
    [limit (assoc params :limit (inc limit))]))

(defn paginate
  "Slice a limit+1 `rows` vector into the list result shape. The cursor
  advances on `:created-at`, matching the shared newest-first order."
  [rows limit]
  (if (seq rows)
    (let [items     (->> (take limit rows)
                         (mapv d/without-nils))
          last-item (peek items)
          has-more? (> (count rows) limit)]
      {:items      items
       :next-since (when has-more? (:created-at last-item))
       :next-id    (when has-more? (:id last-item))})
    {:items []}))
