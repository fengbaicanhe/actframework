package act.handler.event;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2018 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.app.ActionContext;
import act.event.ActEvent;
import act.handler.builtin.controller.impl.ReflectedHandlerInvoker;
import org.osgl.$;

/**
 * Emitted when {@link ReflectedHandlerInvoker} is about handling a request.
 *
 * 3rd part plugin or application can listen to this event and do further initialization work
 * to the reflected handler invoker.
 */
public class ReflectedHandlerInvokerInvoke extends ActEvent<ReflectedHandlerInvoker> {

    private final ActionContext context;

    public ReflectedHandlerInvokerInvoke(ReflectedHandlerInvoker source, ActionContext context) {
        super(source);
        this.context = $.notNull(context);
    }

    public ActionContext context() {
         return context;
    }

}
