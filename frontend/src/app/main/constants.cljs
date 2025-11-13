;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.constants)

(def viewport-width 4000)
(def viewport-height 4000)

(def frame-start-x 1200)
(def frame-start-y 1200)

(def grid-x-axis 10)
(def grid-y-axis 10)

;; Before changing these values also check:
;; frontend/src/app/main/ui/workspace/sidebar/common/sidebar.scss

(def right-sidebar-default-width 318)
(def right-sidebar-default-max-width 768)

(def left-sidebar-default-width 318)
(def left-sidebar-default-max-width 500)

(def page-metadata
  "Default data for page metadata."
  {:grid-x-axis grid-x-axis
   :grid-y-axis grid-y-axis
   :grid-color "var(--df-secondary)"
   :grid-alignment true
   :background "var(--app-white)"})

(def size-presets
  [{:name "APPLE"}
   {:name "iPhone 16"
    :width 393
    :height 852}
   {:name "iPhone 16 Pro"
    :width 402
    :height 874}
   {:name "iPhone 16 Pro Max"
    :width 440
    :height 956}
   {:name "iPhone 16 Plus"
    :width 430
    :height 932}
   {:name "14/15 Pro Max"
    :width 430
    :height 932}
   {:name "iPhone 15/15 Pro"
    :width 393
    :height 852}
   {:name "iPhone 13/14 "
    :width 390
    :height 844}
   {:name "iPhone 14 Plus"
    :width 428
    :height 926}
   {:name "iPhone 13 Mini"
    :width 375
    :height 812}
   {:name "iPhone SE"
    :width 320
    :height 568}
   {:name "iPhone 12/12 Pro"
    :width 390
    :height 844}
   {:name "iPhone 12 Mini"
    :width 360
    :height 780}
   {:name "iPhone 12 Pro Max"
    :width 428
    :height 926}
   {:name "iPhone X/XS/11 Pro"
    :width 375
    :height 812}
   {:name "iPhone XS Max/XR/11"
    :width 414
    :height 896}
   {:name "iPad"
    :width 768
    :height 1024}
   {:name "iPad Mini 8.3in"
    :width 744
    :height 1133}
   {:name "iPad Pro 10.5in"
    :width 834
    :height 1112}
   {:name "iPad Pro 11in"
    :width 834
    :height 1194}
   {:name "iPad Pro 12.9in"
    :width 1027
    :height 1366}
   {:name "Watch Series 10"
    :width 416
    :height 496}
   {:name "Watch 45mm"
    :width 396
    :height 484}
   {:name "Watch 44mm"
    :width 368
    :height 448}
   {:name "Watch 42mm"
    :width 312
    :height 390}
   {:name "Watch 41mm"
    :width 352
    :height 430}
   {:name "Watch 40mm"
    :width 324
    :height 394}
   {:name "Watch 38mm"
    :width 272
    :height 340}
   {:name "MacBook Air"
    :width 1280
    :height 832}
   {:name "MacBook Pro 14in"
    :width 1512
    :height 982}
   {:name "MacBook Pro 16in"
    :width 1728
    :height 1117}

   {:name "ANDROID"}
   {:name "Expanded"
    :width 1280
    :height 800}
   {:name "Compact"
    :width 412
    :height 917}
   {:name "Large"
    :width 360
    :height 800}
   {:name "Medium"
    :width 700
    :height 840}
   {:name "Small"
    :width 360
    :height 640}
   {:name "Mobile"
    :width 360
    :height 640}
   {:name "Tablet"
    :width 768
    :height 1024}
   {:name "Google Pixel 7 Pro"
    :width 1440
    :height 3120}
   {:name "Google Pixel 6a/6"
    :width 1080
    :height 2400}
   {:name "Google Pixel 4a/5"
    :width 393
    :height 851}
   {:name "Samsung Galaxy S22"
    :width 1080
    :height 2340}
   {:name "Samsung Galaxy S20+"
    :width 384
    :height 854}
   {:name "Samsung Galaxy A71/A51"
    :width 412
    :height 914}

   {:name "MICROSOFT"}
   {:name "Surface Pro 3"
    :width 1440
    :height 960}
   {:name "Surface Pro 4/5/6/7"
    :width 1368
    :height 912}
   {:name "Surface Pro 8"
    :width 140
    :height 960}

   {:name "ReMarkable"}
   {:name "Remarkable 2"
    :width 1404
    :height 1872}
   {:name "Remarkable Pro"
    :width 1620
    :height 2160}

   {:name "WEB"}
   {:name "Web 1280"
    :width 1280
    :height 800}
   {:name "Web 1366"
    :width 1366
    :height 768}
   {:name "Web 1024"
    :width 1024
    :height 768}
   {:name "Web 1920"
    :width 1920
    :height 1080}

   {:name "MIXED"}
   {:name "Desktop/Wireframe"
    :width 1440
    :height 1024}
   {:name "TV"
    :width 1280
    :height 720}
   {:name "Slide 16:9"
    :width 1920
    :height 1080}
   {:name "Slide 4:3"
    :width 1027
    :height 768}

   {:name "PRINT (96dpi)"}
   {:name "A0"
    :width 3179
    :height 4494}
   {:name "A1"
    :width 2245
    :height 3179}
   {:name "A2"
    :width 1587
    :height 2245}
   {:name "A3"
    :width 1123
    :height 1587}
   {:name "A4"
    :width 794
    :height 1123}
   {:name "A5"
    :width 559
    :height 794}
   {:name "A6"
    :width 397
    :height 559}
   {:name "Letter"
    :width 816
    :height 1054}
   {:name "DIN Lang"
    :width 835
    :height 413}

   {:name "SOCIAL MEDIA"}
   {:name "Instagram profile"
    :width 320
    :height 320}
   {:name "Instagram post"
    :width 1080
    :height 1350}
   {:name "Instagram story"
    :width 1080
    :height 1920}
   {:name "Facebook profile"
    :width 720
    :height 720}
   {:name "Facebook cover"
    :width 820
    :height 312}
   {:name "Facebook post"
    :width 1200
    :height 630}
   {:name "LinkedIn profile"
    :width 400
    :height 400}
   {:name "LinkedIn cover"
    :width 1584
    :height 396}
   {:name "LinkedIn post"
    :width 520
    :height 320}
   {:name "Bluesky profile"
    :width 400
    :height 400}
   {:name "Bluesky cover"
    :width 3000
    :height 1000}
   {:name "Bluesky post"
    :width 1080
    :height 1350}
   {:name "X profile"
    :width 400
    :height 400}
   {:name "X header"
    :width 1500
    :height 500}
   {:name "X post"
    :width 1024
    :height 512}
   {:name "YouTube profile"
    :width 800
    :height 800}
   {:name "YouTube banner"
    :width 2560
    :height 1440}
   {:name "YouTube cover"
    :width 2048
    :height 1152}
   {:name "YouTube thumb"
    :width 1280
    :height 720}])

(def max-input-length 255)
