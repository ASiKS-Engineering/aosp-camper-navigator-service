# AOSP Camper Navigator Service

System-server service for the ASiKS Camper Navigator.

## Purpose

This repository contains the AOSP-side service that controls the boot-time
and persistent display state of the Camper Navigator.

The intended behavior is:

- `HOME`
  - Launcher remains the foreground Home application.
  - Navigator is not forced to the foreground.

- `FULLSCREEN`
  - Launcher is allowed to start normally.
  - After AOSP reaches `PHASE_BOOT_COMPLETED`, the Navigator is brought to
    the foreground.
  - Launcher remains alive as the HOME task underneath the Navigator.

The Navigator is deliberately NOT registered as the Android HOME activity.

## Architecture

```text
                         system_server
                              |
                              v
                 CamperNavigatorService
                              |
                +-------------+-------------+
                |                           |
             HOME                      FULLSCREEN
                |                           |
                v                           v
           Launcher                  Navigator Activity
           remains TOP                    becomes TOP
