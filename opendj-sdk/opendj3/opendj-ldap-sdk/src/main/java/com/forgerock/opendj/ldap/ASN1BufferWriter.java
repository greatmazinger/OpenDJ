/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */
package com.forgerock.opendj.ldap;



import static org.forgerock.opendj.asn1.ASN1Constants.BOOLEAN_VALUE_FALSE;
import static org.forgerock.opendj.asn1.ASN1Constants.BOOLEAN_VALUE_TRUE;
import static org.forgerock.opendj.ldap.CoreMessages.ERR_ASN1_SEQUENCE_WRITE_NOT_STARTED;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.asn1.ASN1Writer;
import org.forgerock.opendj.asn1.AbstractASN1Writer;
import org.forgerock.opendj.ldap.ByteSequence;
import org.forgerock.opendj.ldap.ByteStringBuilder;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.ByteBufferWrapper;

import com.forgerock.opendj.util.StaticUtils;



/**
 * Grizzly ASN1 writer implementation.
 */
final class ASN1BufferWriter extends AbstractASN1Writer implements ASN1Writer,
    Cacheable
{
  private class ChildSequenceBuffer implements SequenceBuffer
  {
    private SequenceBuffer parent;

    private ChildSequenceBuffer child;

    private final ByteStringBuilder buffer = new ByteStringBuilder(
        BUFFER_INIT_SIZE);



    public SequenceBuffer endSequence() throws IOException
    {
      writeLength(parent, buffer.length());
      parent.writeByteArray(buffer.getBackingArray(), 0, buffer.length());

      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 END SEQUENCE(length=%d)", buffer.length()));
      }

      return parent;
    }



    public SequenceBuffer startSequence(final byte type) throws IOException
    {
      if (child == null)
      {
        child = new ChildSequenceBuffer();
        child.parent = this;
      }

      buffer.append(type);
      child.buffer.clear();

      return child;
    }



    public void writeByte(final byte b) throws IOException
    {
      buffer.append(b);
    }



    public void writeByteArray(final byte[] bs, final int offset,
        final int length) throws IOException
    {
      buffer.append(bs, offset, length);
    }
  }



  private static final class RecyclableBuffer extends ByteBufferWrapper
  {
    private volatile boolean usable = true;



    private RecyclableBuffer()
    {
      visible = ByteBuffer.allocate(BUFFER_INIT_SIZE);
      allowBufferDispose = true;
    }



    @Override
    public void dispose()
    {
      usable = true;
    }



    /**
     * Ensures that the specified number of additional bytes will fit in the
     * buffer and resizes it if necessary.
     *
     * @param size
     *          The number of additional bytes.
     */
    public void ensureAdditionalCapacity(final int size)
    {
      final int newCount = visible.position() + size;
      if (newCount > visible.capacity())
      {
        final ByteBuffer newByteBuffer = ByteBuffer.allocate(Math.max(visible
            .capacity() << 1, newCount));
        visible.flip();
        visible = newByteBuffer.put(visible);
      }
    }
  }



  private class RootSequenceBuffer implements SequenceBuffer
  {
    private ChildSequenceBuffer child;



    public SequenceBuffer endSequence() throws IOException
    {
      final LocalizableMessage message = ERR_ASN1_SEQUENCE_WRITE_NOT_STARTED
          .get();
      throw new IllegalStateException(message.toString());
    }



    public SequenceBuffer startSequence(final byte type) throws IOException
    {
      if (child == null)
      {
        child = new ChildSequenceBuffer();
        child.parent = this;
      }

      outBuffer.ensureAdditionalCapacity(1);
      outBuffer.put(type);
      child.buffer.clear();

      return child;
    }



    public void writeByte(final byte b) throws IOException
    {
      outBuffer.ensureAdditionalCapacity(1);
      outBuffer.put(b);
    }



    public void writeByteArray(final byte[] bs, final int offset,
        final int length) throws IOException
    {
      outBuffer.ensureAdditionalCapacity(length);
      outBuffer.put(bs, offset, length);
    }
  }



  private interface SequenceBuffer
  {
    public SequenceBuffer endSequence() throws IOException;



    public SequenceBuffer startSequence(byte type) throws IOException;



    public void writeByte(byte b) throws IOException;



    public void writeByteArray(byte[] bs, int offset, int length)
        throws IOException;
  }



  private static final int BUFFER_INIT_SIZE = 1024;
  private final static ThreadCache.CachedTypeIndex<ASN1BufferWriter> WRITER_INDEX = ThreadCache
      .obtainIndex(ASN1BufferWriter.class, 1);



  static ASN1BufferWriter getWriter()
  {
    ASN1BufferWriter asn1Writer = ThreadCache.takeFromCache(WRITER_INDEX);
    if (asn1Writer == null)
    {
      asn1Writer = new ASN1BufferWriter();
    }

    if (!asn1Writer.outBuffer.usable)
    {
      // If the output buffer is unusable, create a new one.
      asn1Writer.outBuffer = new RecyclableBuffer();
    }
    asn1Writer.outBuffer.clear();
    return asn1Writer;
  }



  private SequenceBuffer sequenceBuffer;
  private RecyclableBuffer outBuffer;

  private final RootSequenceBuffer rootBuffer;



  /**
   * Creates a new ASN.1 writer that writes to a StreamWriter.
   */
  private ASN1BufferWriter()
  {
    this.sequenceBuffer = this.rootBuffer = new RootSequenceBuffer();
    this.outBuffer = new RecyclableBuffer();
  }



  /**
   * Closes this ASN.1 writer and the underlying outputstream. Any unfinished
   * sequences will be ended.
   *
   * @throws IOException
   *           if an error occurs while closing the stream.
   */
  public void close() throws IOException
  {
    outBuffer = null;
  }



  /**
   * Flushes the stream.
   *
   * @throws IOException
   *           If an I/O error occurs
   */
  public void flush() throws IOException
  {
    // Do nothing
  }



  public void recycle()
  {
    sequenceBuffer = rootBuffer;
    outBuffer.clear();
    ThreadCache.putToCache(WRITER_INDEX, this);
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeBoolean(final byte type, final boolean booleanValue)
      throws IOException
  {
    sequenceBuffer.writeByte(type);
    writeLength(sequenceBuffer, 1);
    sequenceBuffer.writeByte(booleanValue ? BOOLEAN_VALUE_TRUE
        : BOOLEAN_VALUE_FALSE);

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String.format(
          "WRITE ASN.1 BOOLEAN(type=0x%x, length=%d, value=%s)", type, 1,
          String.valueOf(booleanValue)));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeEndSequence() throws IOException,
      IllegalStateException
  {
    sequenceBuffer = sequenceBuffer.endSequence();

    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeEndSet() throws IOException
  {
    return writeEndSequence();
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeEnumerated(final byte type, final int intValue)
      throws IOException
  {
    return writeInteger(type, intValue);
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeInteger(final byte type, final int intValue)
      throws IOException
  {
    sequenceBuffer.writeByte(type);
    if (((intValue < 0) && ((intValue & 0xFFFFFF80) == 0xFFFFFF80))
        || ((intValue & 0x0000007F) == intValue))
    {
      writeLength(sequenceBuffer, 1);
      sequenceBuffer.writeByte((byte) (intValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 1,
            intValue));
      }
    }
    else if (((intValue < 0) && ((intValue & 0xFFFF8000) == 0xFFFF8000))
        || ((intValue & 0x00007FFF) == intValue))
    {
      writeLength(sequenceBuffer, 2);
      sequenceBuffer.writeByte((byte) ((intValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (intValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 2,
            intValue));
      }
    }
    else if (((intValue < 0) && ((intValue & 0xFF800000) == 0xFF800000))
        || ((intValue & 0x007FFFFF) == intValue))
    {
      writeLength(sequenceBuffer, 3);
      sequenceBuffer.writeByte((byte) ((intValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((intValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (intValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 3,
            intValue));
      }
    }
    else
    {
      writeLength(sequenceBuffer, 4);
      sequenceBuffer.writeByte((byte) ((intValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((intValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((intValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (intValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 4,
            intValue));
      }
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeInteger(final byte type, final long longValue)
      throws IOException
  {
    sequenceBuffer.writeByte(type);
    if (((longValue < 0) && ((longValue & 0xFFFFFFFFFFFFFF80L) == 0xFFFFFFFFFFFFFF80L))
        || ((longValue & 0x000000000000007FL) == longValue))
    {
      writeLength(sequenceBuffer, 1);
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 1,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFFFFFFFFFFFF8000L) == 0xFFFFFFFFFFFF8000L))
        || ((longValue & 0x0000000000007FFFL) == longValue))
    {
      writeLength(sequenceBuffer, 2);
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 2,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFFFFFFFFFF800000L) == 0xFFFFFFFFFF800000L))
        || ((longValue & 0x00000000007FFFFFL) == longValue))
    {
      writeLength(sequenceBuffer, 3);
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 3,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFFFFFFFF80000000L) == 0xFFFFFFFF80000000L))
        || ((longValue & 0x000000007FFFFFFFL) == longValue))
    {
      writeLength(sequenceBuffer, 4);
      sequenceBuffer.writeByte((byte) ((longValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 4,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFFFFFF8000000000L) == 0xFFFFFF8000000000L))
        || ((longValue & 0x0000007FFFFFFFFFL) == longValue))
    {
      writeLength(sequenceBuffer, 5);
      sequenceBuffer.writeByte((byte) ((longValue >> 32) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 5,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFFFF800000000000L) == 0xFFFF800000000000L))
        || ((longValue & 0x00007FFFFFFFFFFFL) == longValue))
    {
      writeLength(sequenceBuffer, 6);
      sequenceBuffer.writeByte((byte) ((longValue >> 40) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 32) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 6,
            longValue));
      }
    }
    else if (((longValue < 0) && ((longValue & 0xFF80000000000000L) == 0xFF80000000000000L))
        || ((longValue & 0x007FFFFFFFFFFFFFL) == longValue))
    {
      writeLength(sequenceBuffer, 7);
      sequenceBuffer.writeByte((byte) ((longValue >> 48) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 40) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 32) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 7,
            longValue));
      }
    }
    else
    {
      writeLength(sequenceBuffer, 8);
      sequenceBuffer.writeByte((byte) ((longValue >> 56) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 48) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 40) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 32) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 24) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 16) & 0xFF));
      sequenceBuffer.writeByte((byte) ((longValue >> 8) & 0xFF));
      sequenceBuffer.writeByte((byte) (longValue & 0xFF));
      if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
      {
        StaticUtils.DEBUG_LOG.finest(String.format(
            "WRITE ASN.1 INTEGER(type=0x%x, length=%d, value=%d)", type, 8,
            longValue));
      }
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeNull(final byte type) throws IOException
  {
    sequenceBuffer.writeByte(type);
    writeLength(sequenceBuffer, 0);

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String.format(
          "WRITE ASN.1 NULL(type=0x%x, length=%d)", type, 0));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeOctetString(final byte type, final byte[] value,
      final int offset, final int length) throws IOException
  {
    sequenceBuffer.writeByte(type);
    writeLength(sequenceBuffer, length);
    sequenceBuffer.writeByteArray(value, offset, length);

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String.format(
          "WRITE ASN.1 OCTETSTRING(type=0x%x, length=%d)", type, length));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeOctetString(final byte type, final ByteSequence value)
      throws IOException
  {
    sequenceBuffer.writeByte(type);
    writeLength(sequenceBuffer, value.length());
    // TODO: Is there a more efficient way to do this?
    for (int i = 0; i < value.length(); i++)
    {
      sequenceBuffer.writeByte(value.byteAt(i));
    }

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String
          .format("WRITE ASN.1 OCTETSTRING(type=0x%x, length=%d)", type, value
              .length()));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeOctetString(final byte type, final String value)
      throws IOException
  {
    sequenceBuffer.writeByte(type);

    if (value == null)
    {
      writeLength(sequenceBuffer, 0);
      return this;
    }

    final byte[] bytes = StaticUtils.getBytes(value);
    writeLength(sequenceBuffer, bytes.length);
    sequenceBuffer.writeByteArray(bytes, 0, bytes.length);

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String.format(
          "WRITE ASN.1 OCTETSTRING(type=0x%x, length=%d, " + "value=%s)", type,
          bytes.length, value));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeStartSequence(final byte type) throws IOException
  {
    // Get a child sequence buffer
    sequenceBuffer = sequenceBuffer.startSequence(type);

    if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINEST))
    {
      StaticUtils.DEBUG_LOG.finest(String.format(
          "WRITE ASN.1 START SEQUENCE(type=0x%x)", type));
    }
    return this;
  }



  /**
   * {@inheritDoc}
   */
  public ASN1Writer writeStartSet(final byte type) throws IOException
  {
    // From an implementation point of view, a set is equivalent to a
    // sequence.
    return writeStartSequence(type);
  }



  Buffer getBuffer()
  {
    outBuffer.usable = false;
    return outBuffer.flip();
  }



  /**
   * Writes the provided value for use as the length of an ASN.1 element.
   *
   * @param buffer
   *          The sequence buffer to write to.
   * @param length
   *          The length to encode for use in an ASN.1 element.
   * @throws IOException
   *           if an error occurs while writing.
   */
  private void writeLength(final SequenceBuffer buffer, final int length)
      throws IOException
  {
    if (length < 128)
    {
      buffer.writeByte((byte) length);
    }
    else if ((length & 0x000000FF) == length)
    {
      buffer.writeByte((byte) 0x81);
      buffer.writeByte((byte) (length & 0xFF));
    }
    else if ((length & 0x0000FFFF) == length)
    {
      buffer.writeByte((byte) 0x82);
      buffer.writeByte((byte) ((length >> 8) & 0xFF));
      buffer.writeByte((byte) (length & 0xFF));
    }
    else if ((length & 0x00FFFFFF) == length)
    {
      buffer.writeByte((byte) 0x83);
      buffer.writeByte((byte) ((length >> 16) & 0xFF));
      buffer.writeByte((byte) ((length >> 8) & 0xFF));
      buffer.writeByte((byte) (length & 0xFF));
    }
    else
    {
      buffer.writeByte((byte) 0x84);
      buffer.writeByte((byte) ((length >> 24) & 0xFF));
      buffer.writeByte((byte) ((length >> 16) & 0xFF));
      buffer.writeByte((byte) ((length >> 8) & 0xFF));
      buffer.writeByte((byte) (length & 0xFF));
    }
  }
}
