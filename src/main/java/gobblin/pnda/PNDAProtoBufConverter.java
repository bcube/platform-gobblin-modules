/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gobblin.pnda;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

import gobblin.configuration.WorkUnitState;
import gobblin.converter.Converter;
import gobblin.converter.DataConversionException;
import gobblin.util.EmptyIterable;
import gobblin.converter.SchemaConversionException;
import gobblin.converter.SingleRecordIterable;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * An implementation of {@link Converter}.
 *
 * <p>
 *   This converter converts the input string schema into an
 *   Avro {@link org.apache.avro.Schema} and each input byte[] document
 *   into an Avro {@link org.apache.avro.generic.GenericRecord}.
 * </p>
 */
public class PNDAProtoBufConverter extends PNDAAbstractConverter {

  private static final Logger log = LoggerFactory.getLogger(PNDAProtoBufConverter.class);
  private Descriptor pbDescriptor = null;
  private FieldDescriptor pbSource = null;
  private FieldDescriptor pbTimestamp = null;


  public void close() throws IOException {
    super.close();
  }

  @Override
  public Schema convertSchema(String topic, WorkUnitState workUnit)
      throws SchemaConversionException {

      Schema schema = super.convertSchema(topic, workUnit);

      FieldDescriptorProto fb1 = buildField("node_id_str", 1, FieldDescriptorProto.Label.LABEL_OPTIONAL, FieldDescriptorProto.Type.TYPE_STRING);
      FieldDescriptorProto fb2 = buildField("msg_timestamp", 10, FieldDescriptorProto.Label.LABEL_OPTIONAL, FieldDescriptorProto.Type.TYPE_UINT64);

      // Construct the proto message type
      DescriptorProto.Builder mMsgTypeBuilder = DescriptorProto.newBuilder();
      mMsgTypeBuilder.setName("Telemetry");
      mMsgTypeBuilder.addField(fb1);
      mMsgTypeBuilder.addField(fb2);
  
      // Construct the file proto file
      FileDescriptorProto.Builder pb = FileDescriptorProto.newBuilder();
      //pb.setSyntax("proto3");
      pb.addMessageType(mMsgTypeBuilder);
      FileDescriptorProto proto = pb.build();
  
      // Compile the descriptors
      try {
        FileDescriptor[] dependencies = {};
        FileDescriptor fileDesc = FileDescriptor.buildFrom(proto, dependencies);
        this.pbDescriptor = fileDesc.findMessageTypeByName("Telemetry");
        ProtobufTopicConfig protoConfig = (ProtobufTopicConfig)getConfig();
        if(protoConfig.hasSource()) {
           this.pbSource = this.pbDescriptor.findFieldByNumber(protoConfig.getSourceTag());
        }
        if(protoConfig.hasTimeStamp()) {
           this.pbTimestamp = this.pbDescriptor.findFieldByNumber(protoConfig.getTimestampTag());
        }
      } catch (DescriptorValidationException error) {
          throw new SchemaConversionException(String.format("Unable to read AVRO schema file %s", error));
      }
      return schema;
  }

  @Override
  public Iterable<GenericRecord> convertRecord(Schema schema, byte[] inputRecord,
                                               WorkUnitState workUnit)
      throws DataConversionException {

      // Parse the message
      try {
        DynamicMessage dmsg = DynamicMessage.parseFrom(this.pbDescriptor, inputRecord);
        // Extract the know fields
        Map<FieldDescriptor,Object> map = dmsg.getAllFields();
        Object source = null;
        Object timestamp = null;
        if(null != pbSource) {
          source = map.get(pbSource);
          log.info("Extracted [" + pbSource.getNumber() + "]: " + source);
        }
        if(null != pbTimestamp) {
          timestamp = map.get(pbTimestamp);
          log.info("Extracted [" + pbTimestamp.getNumber() + "]: " + timestamp);
        }
        return new SingleRecordIterable<GenericRecord>(generateRecord(inputRecord, workUnit, source, timestamp));
      } catch (InvalidProtocolBufferException error) {
          log.error("InvalidProtocolBufferException: "+error);
          writeErrorData(inputRecord, "Unable to deserialize protobuf data");
          return new EmptyIterable<GenericRecord>();
      }
  }

  private static FieldDescriptorProto buildField(String name, int tag, FieldDescriptorProto.Label label, FieldDescriptorProto.Type type) {
    FieldDescriptorProto.Builder fb = FieldDescriptorProto.newBuilder();
    fb.setType(type);
    fb.setLabel(label);
    fb.setName(name);
    fb.setNumber(tag);
    return fb.build();
  }

  public boolean valdidateConfig(TopicConfig config) {
    return (config instanceof ProtobufTopicConfig);
  }
}