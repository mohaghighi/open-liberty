/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.myfaces.util;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 *
 * @author Leonardo Uribe
 */
public class CDataEndEscapeFilterWriter extends FilterWriter
{
    private char c1;
    private char c2;
    private int pos;

    public CDataEndEscapeFilterWriter(Writer out)
    {
        super(out);
        pos = 0;
    }

    @Override
    public void write(int c) throws IOException
    {
        super.write(c);
        c1 = c2;
        c2 = (char) c;
        pos ++;
        if (pos > 2)
        {
            if (c1 == ']' && c2 == ']' && c == '>')
            {
                //"]]><![CDATA[]]]]><![CDATA[>"
                out.write("<![CDATA[]]]]><![CDATA[>");
            }
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        int index = off;
        StringBuilder sb = null;
        for (int i = 0; i < len; i++)
        {
            char c = cbuf[off+i];
            if (c1 == ']' && c2 == ']' && c == '>')
            {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(cbuf, index, i+1 - ( index - off ));
                index = off+i+1;
                sb.append("<![CDATA[]]]]><![CDATA[>");
            }
            c1 = c2;
            c2 = c;
            pos++;
        }
        if (sb != null)
        {
            if (index < off+len) {
                sb.append(cbuf, index, off+len-index);
            }
            out.write(sb.toString());
        } else {
            out.write(cbuf, off, len);
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException
    {
        int index = off;
        StringBuilder sb = null;
        for (int i = 0; i < len; i++)
        {
            char c = str.charAt(off+i);
            if (c1 == ']' && c2 == ']' && c == '>')
            {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(str, index, i+1 - ( index - off ) );
                index = off+i+1;
                sb.append("<![CDATA[]]]]><![CDATA[>");
            }
            c1 = c2;
            c2 = c;
            pos++;
        }
        if (sb != null)
        {
            if (index < off+len) {
                sb.append(str, index, off+len-index);
            }
            out.write(sb.toString());
        } else {
            out.write(str, off, len);
        }
    }
}
