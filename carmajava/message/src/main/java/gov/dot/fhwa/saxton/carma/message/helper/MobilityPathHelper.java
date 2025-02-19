/*
 * Copyright (C) 2018-2019 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.message.helper;

import cav_msgs.MobilityPath;

/**
 * This is the helper class for encoding Mobility Path message.
 * All fields' unit and type in this class match J2735 Mobility Request message.
 */
public class MobilityPathHelper {
    private MobilityHeaderHelper headerHelper;
    private MobilityTrajectoryHelper trajectoryHelper;
    
    public MobilityPathHelper(MobilityPath path) {
        this.headerHelper = new MobilityHeaderHelper(path.getHeader());
        this.trajectoryHelper = new MobilityTrajectoryHelper(path.getTrajectory());
    }

    public MobilityHeaderHelper getHeaderHelper() {
        return headerHelper;
    }

    public MobilityTrajectoryHelper getTrajectoryHelper() {
        return trajectoryHelper;
    }
}