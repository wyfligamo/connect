/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * http://www.mirthcorp.com
 *
 * The software in this package is published under the terms of the MPL
 * license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */

package com.mirth.connect.server.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;

import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.Operations;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.model.filters.EventFilter;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

public class EventServlet extends MirthServlet {
    private Logger logger = Logger.getLogger(this.getClass());

    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isUserLoggedIn(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        } else {
            try {
                EventController eventController = ControllerFactory.getFactory().createEventController();
                ObjectXMLSerializer serializer = new ObjectXMLSerializer();
                PrintWriter out = response.getWriter();
                Operation operation = Operations.getOperation(request.getParameter("op"));
                String uid = null;
                boolean useNewTempTable = false;
                Map<String, Object> parameterMap = new HashMap<String, Object>();
                
                if (StringUtils.isNotBlank(request.getParameter("uid"))) {
                    uid = request.getParameter("uid");
                    useNewTempTable = true;
                } else {
                    uid = request.getSession().getId();
                }

                if (operation.equals(Operations.EVENT_CREATE_TEMP_TABLE)) {
                    EventFilter eventFilter = (EventFilter) serializer.fromXML(request.getParameter("filter"));
                    parameterMap.put("filter", eventFilter);

                    if (!isUserAuthorized(request, parameterMap)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        response.setContentType("text/plain");
                        out.println(eventController.createEventTempTable(eventFilter, uid, useNewTempTable));
                    }
                } else if (operation.equals(Operations.EVENT_REMOVE_FILTER_TABLES)) {
                    if (!isUserAuthorized(request, null)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        eventController.removeEventFilterTable(uid);
                    }
                } else if (operation.equals(Operations.EVENT_GET_BY_PAGE)) {
                    if (!isUserAuthorized(request, null)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        int page = Integer.parseInt(request.getParameter("page"));
                        int pageSize = Integer.parseInt(request.getParameter("pageSize"));
                        int max = Integer.parseInt(request.getParameter("maxEvents"));
                        response.setContentType("application/xml");
                        out.print(serializer.toXML(eventController.getEventsByPage(page, pageSize, max, uid)));
                    }
                } else if (operation.equals(Operations.EVENT_GET_BY_PAGE_LIMIT)) {
                    EventFilter eventFilter = (EventFilter) serializer.fromXML(request.getParameter("filter"));
                    parameterMap.put("filter", eventFilter);

                    if (!isUserAuthorized(request, parameterMap)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        int page = Integer.parseInt(request.getParameter("page"));
                        int pageSize = Integer.parseInt(request.getParameter("pageSize"));
                        int max = Integer.parseInt(request.getParameter("maxEvents"));
                        response.setContentType("application/xml");
                        out.print(serializer.toXML(eventController.getEventsByPageLimit(page, pageSize, max, uid, eventFilter)));
                    }
                } else if (operation.equals(Operations.EVENT_REMOVE_ALL)) {
                    if (!isUserAuthorized(request, null)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        eventController.removeAllEvents();
                    }
                }
            } catch (Throwable t) {
                logger.error(ExceptionUtils.getStackTrace(t));
                throw new ServletException(t);
            }
        }
    }
}