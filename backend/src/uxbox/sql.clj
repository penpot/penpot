;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.sql
  (:require [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "sql/projects.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/pages.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/users.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/emails.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/images.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/icons.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/kvstore.sql" {:quoting :ansi :fn-suffix ""})
(hugsql/def-sqlvec-fns "sql/workers.sql" {:quoting :ansi :fn-suffix ""})

